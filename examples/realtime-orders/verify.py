#!/usr/bin/env python3
"""Run the isolated MySQL–Kafka–Flink acceptance scenario."""
import argparse
from datetime import date, datetime, time as clock_time, timedelta, timezone
import json
import os
import shlex
import tempfile
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid
from zoneinfo import ZoneInfo

LAB = Path(__file__).resolve().parent
TOPIC = "orders.ods.v1"


def run(command, *, stdin=None, timeout=45):
    completed = subprocess.run(command, input=stdin, text=True, capture_output=True, timeout=timeout)
    if completed.returncode:
        raise RuntimeError(f"{command[:4]} exited {completed.returncode}\n{completed.stdout}\n{completed.stderr}")
    return completed.stdout.strip()


def wait_for(description, predicate, seconds=100):
    until = time.monotonic() + seconds
    last = None
    while time.monotonic() < until:
        try:
            if value := predicate():
                return value
        except (OSError, RuntimeError, KeyError, ValueError) as error:
            last = error
        time.sleep(1)
    raise RuntimeError(f"Timed out: {description}. Last error: {last}")


def business_day_bounds(business_day):
    zone = ZoneInfo("Asia/Shanghai")
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    start = datetime.combine(business_day, clock_time.min, tzinfo=zone)
    end = datetime.combine(business_day, clock_time.max, tzinfo=zone)
    millisecond = timedelta(milliseconds=1)
    return (start - epoch) // millisecond, (end - epoch) // millisecond


def ads_differences(rows):
    outputs = {"stream": {}, "batch": {}}
    for row in rows:
        source, biz_day, region, count, amount = json.loads(row)
        outputs[source][(biz_day, region)] = {"paid_order_count": count, "gmv_cents": int(amount)}
    stream, batch = outputs["stream"], outputs["batch"]
    differences = []
    for key in sorted(stream.keys() | batch.keys()):
        actual, expected = stream.get(key), batch.get(key)
        if actual == expected:
            continue
        reason = "value_mismatch"
        if actual is None:
            reason = "missing_stream"
        elif expected is None:
            reason = "missing_batch"
        differences.append({
            "biz_date": key[0],
            "region": key[1],
            "stream": actual,
            "batch": expected,
            "difference": reason,
        })
    return {"stream_keys": len(stream), "batch_keys": len(batch), "differences": differences}


class Lab:
    def __init__(self, project):
        self.command = ["docker", "compose", "-p", project, "-f", str(LAB / "compose.yml")]
        self.rest_url = None

    def compose(self, *args, stdin=None, timeout=45):
        return run([*self.command, *args], stdin=stdin, timeout=timeout)

    def mysql(self, sql, *, raw=False):
        raw_flag = " --raw" if raw else ""
        return self.compose("exec", "-T", "mysql", "sh", "-ec",
                            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 --connect-timeout=5 -uroot -NB' + raw_flag,
                            stdin="SET time_zone = '+00:00';\n" + sql)

    def kafka(self, tool, *args, stdin=None):
        return self.compose("exec", "-T", "kafka", f"/opt/kafka/bin/kafka-{tool}.sh",
                            "--bootstrap-server", "kafka:9092", *args, stdin=stdin)

    def rest(self, path):
        with urllib.request.urlopen(self.rest_url + path, timeout=5) as response:
            return json.load(response)

    def setup(self):
        self.compose("up", "-d", "--no-build", timeout=400)
        wait_for("MySQL schema and fixture",
                 lambda: self.mysql("SELECT COUNT(*) FROM orders_source.orders;") == "3", seconds=180)
        wait_for("Kafka broker", lambda: self.kafka("topics", "--create", "--if-not-exists",
                                                   "--topic", TOPIC, "--partitions", "1",
                                                   "--replication-factor", "1",
                                                   "--config", "cleanup.policy=delete") is not None)
        self.rest_url = "http://" + self.compose("port", "jobmanager", "8081")
        wait_for("Flink task slots", lambda: self.rest("/overview")["slots-total"] >= 2)

    def submit(self):
        self.compose("exec", "-T", "jobmanager", "env", "FLINK_HOME=/opt/flink", "bash",
                     "/cdc/bin/flink-cdc.sh", "/lab/orders-cdc.yaml", timeout=100)
        cdc = self.job("Orders MySQL CDC")
        self.submit_sql("warehouse.sql")
        warehouse = self.job("Orders realtime warehouse")
        return [cdc, warehouse]

    def submit_sql(self, filename, *, run_id=None):
        script = (LAB / filename).read_text(encoding="utf-8")
        if run_id:
            script = script.replace("@@RUN_ID@@", run_id)
        shared = (LAB / "paid-order-views.sql").read_text(encoding="utf-8")
        script = script.replace("-- INCLUDE paid-order-views.sql", shared.rstrip())
        return self.compose("exec", "-T", "jobmanager", "sh", "-ec",
                     """input=$(mktemp); trap 'rm -f "$input"' EXIT; cat > "$input"; /opt/flink/bin/sql-client.sh -f "$input" """,
                     stdin=script, timeout=110)

    def reconcile_batch(self, business_day=None, *, publish=False):
        if publish and business_day is not None:
            raise ValueError("Publishing requires a full reconciliation")
        run_id = uuid.uuid4().hex
        snapshot = f"orders_serving.batch_snapshot_{run_id}"
        batch = f"orders_serving.batch_ads_{run_id}"
        where = ""
        if business_day:
            start, end = business_day_bounds(business_day)
            where = f"WHERE paid_epoch_ms BETWEEN {start} AND {end}"

        if self.rest_url is None:
            self.rest_url = "http://" + self.compose("port", "jobmanager", "8081")

        try:
            self.mysql(f"""
                CREATE TABLE {snapshot} LIKE orders_source.orders;
                CREATE TABLE {batch} LIKE orders_serving.ads_region_day_batch;
                INSERT INTO {snapshot} SELECT * FROM orders_source.orders {where};
            """)
            self.submit_sql("batch-reconcile.sql", run_id=run_id)
            job_name = f"Orders bounded reconciliation {run_id}"
            terminal_states = {"FINISHED", "FAILED", "CANCELED"}
            job = wait_for(f"{job_name} terminal state", lambda: next((
                job for job in self.rest("/jobs/overview")["jobs"]
                if job["name"] == job_name and job["state"] in terminal_states
            ), None))
            if job["state"] != "FINISHED":
                raise RuntimeError(f"{job_name}: {job['state']}")
            report = self.ads_report(business_day, run_id=run_id)
            if publish:
                if not report["batch_keys"] or report["differences"]:
                    raise RuntimeError(f"Flink batch/stream mismatch: {json.dumps(report, ensure_ascii=False)}")
                self.mysql(f"""
                    START TRANSACTION;
                    DELETE FROM orders_serving.ads_region_day_batch;
                    INSERT INTO orders_serving.ads_region_day_batch SELECT * FROM {batch};
                    COMMIT;
                """)
            return report
        finally:
            original_error = sys.exc_info()[1]
            try:
                self.mysql(f"DROP TABLE IF EXISTS {batch}, {snapshot};")
            except (OSError, RuntimeError, subprocess.SubprocessError) as cleanup_error:
                if original_error is None:
                    raise
                print(f"Batch staging cleanup failed: {cleanup_error}", file=sys.stderr)

    def ads_report(self, business_day=None, *, run_id=None):
        where = f"WHERE biz_date = '{business_day.isoformat()}'" if business_day else ""
        batch = f"batch_ads_{run_id}" if run_id else "ads_region_day_batch"
        columns = "biz_date,region,paid_order_count,CAST(gmv_cents AS CHAR)"
        rows = self.mysql(f"""
            SELECT JSON_ARRAY('stream',{columns}) FROM orders_serving.ads_region_day {where}
            UNION ALL
            SELECT JSON_ARRAY('batch',{columns}) FROM orders_serving.{batch} {where};
        """, raw=True).splitlines()
        return ads_differences(rows)

    def verify_day_recomputation(self):
        business_day = date(2026, 11, 17)
        other_day = date(2026, 11, 18)
        untouched = self.ads_report(other_day)
        self.mysql("UPDATE orders_source.orders SET amount_cents=13000,change_version=3 WHERE order_id=101;")
        self.assert_current("historical payment correction")
        before = self.ads_report(business_day)
        if [item["difference"] for item in before["differences"]] != ["value_mismatch"]:
            raise RuntimeError(f"Expected a stale published amount: {before}")
        fresh = self.reconcile_batch(business_day)
        if not fresh["batch_keys"] or fresh["differences"]:
            raise RuntimeError(f"Fresh per-day reconciliation failed: {fresh}")
        if self.ads_report(business_day) != before or self.ads_report(other_day) != untouched:
            raise RuntimeError("Per-day recomputation changed a published batch result")
        self.mysql("UPDATE orders_source.orders SET amount_cents=12000,change_version=4 WHERE order_id=101;")
        self.assert_current("fixture restored after per-day recomputation")
        print("PASS fresh per-day ADS reconciliation preserved published batch results", flush=True)

    def verify_windows(self):
        output = self.submit_sql("window-demo.sql")
        rows = {}
        for line in output.splitlines():
            if not line.strip().startswith("|"):
                continue
            fields = [field.strip() for field in line.strip().strip("|").split("|")]
            if len(fields) == 6 and fields[0] == "+I" and fields[1] in {"TUMBLE", "HOP", "CUMULATE"}:
                _, kind, start, end, region, cents = fields
                rows[(kind, start[11:16], end[11:16], region)] = int(cents)
        expected = {
            ("TUMBLE", "09:00", "09:01", "east"): 200,
            ("TUMBLE", "09:01", "09:02", "east"): 50,
            ("TUMBLE", "09:00", "09:01", "west"): 90,
            ("HOP", "08:59", "09:01", "east"): 200,
            ("HOP", "09:00", "09:02", "east"): 250,
            ("HOP", "09:01", "09:03", "east"): 50,
            ("HOP", "08:59", "09:01", "west"): 90,
            ("HOP", "09:00", "09:02", "west"): 90,
            ("CUMULATE", "09:00", "09:01", "east"): 200,
            ("CUMULATE", "09:00", "09:02", "east"): 250,
            ("CUMULATE", "09:00", "09:01", "west"): 90,
            ("CUMULATE", "09:00", "09:02", "west"): 90,
        }
        if rows != expected:
            raise RuntimeError(f"Flink window mismatch: expected {expected}, got {rows}\n{output}")
        print("PASS event-time TUMBLE, HOP and CUMULATE: 12 expected windows", flush=True)

    def job(self, name):
        return wait_for(name, lambda: next((job["jid"] for job in self.rest("/jobs/overview")["jobs"]
                                            if job["name"] == name and job["state"] == "RUNNING"), None))

    def result(self, operation):
        return self.mysql(operation).splitlines()

    def assert_current(self, label):
        clock = "TIMESTAMPADD(SECOND, paid_epoch_ms DIV 1000, '1970-01-01 08:00:00')"
        date = f"DATE({clock})"
        minute = f"DATE_FORMAT({clock}, '%Y-%m-%d %H:%i:00')"
        source = "FROM orders_source.orders WHERE status = 'PAID'"
        checks = [
            (f"SELECT order_id,{date},{minute},region,amount_cents,change_version {source} ORDER BY order_id;",
             "SELECT order_id,biz_date,DATE_FORMAT(minute_start,'%Y-%m-%d %H:%i:00'),region,amount_cents,change_version "
             "FROM orders_serving.dwd_paid_orders ORDER BY order_id;"),
            (f"SELECT {date},{minute},region,COUNT(*),SUM(amount_cents) {source} GROUP BY 1,2,3 ORDER BY 1,2,3;",
             "SELECT biz_date,DATE_FORMAT(minute_start,'%Y-%m-%d %H:%i:00'),region,paid_order_count,gmv_cents "
             "FROM orders_serving.dws_region_minute ORDER BY 1,2,3;"),
            (f"SELECT {date},region,COUNT(*),SUM(amount_cents) {source} GROUP BY 1,2 ORDER BY 1,2;",
             "SELECT biz_date,region,paid_order_count,gmv_cents FROM orders_serving.ads_region_day ORDER BY 1,2;"),
        ]
        for stage, (expected_sql, live_sql) in zip(("DWD", "DWS", "ADS"), checks):
            expected = self.result(expected_sql)

            def matches():
                actual = self.result(live_sql)
                if actual != expected:
                    raise ValueError(f"{stage}: expected {expected}, got {actual}")
                return True

            wait_for(f"{label} {stage} reconciles", matches)
        print(f"PASS {label}: DWD, DWS and ADS reconcile with source", flush=True)

    def records(self, count):
        output = self.kafka("console-consumer", "--topic", TOPIC, "--partition", "0", "--offset", "earliest",
                            "--max-messages", str(count), "--timeout-ms", "12000", "--property", "print.key=true",
                            "--consumer-property", "isolation.level=read_committed")
        records = [(json.loads(key), json.loads(value)) for key, value in
                   (line.split("\t", 1) for line in output.splitlines() if "\t" in line)]
        if len(records) != count:
            raise RuntimeError(f"Expected {count} ODS records, got {len(records)}")
        return records

    def checkpoint(self, job, kind):
        return self.rest(f"/jobs/{job}/checkpoints")["latest"][kind]

    def recover(self, jobs):
        baseline = [wait_for("completed checkpoint", lambda: self.checkpoint(job, "completed"))["id"] for job in jobs]
        for job, checkpoint_id in zip(jobs, baseline):
            # Leave time for any checkpoint already in progress when the outputs reconciled.
            wait_for("checkpoint past incremental changes",
                     lambda: self.checkpoint(job, "completed")["id"] >= checkpoint_id + 2)
        self.compose("stop", "-t", "2", "taskmanager")
        try:
            self.mysql("""
                UPDATE orders_source.orders SET status='PAID',amount_cents=8000,change_version=3 WHERE order_id=102;
                UPDATE orders_source.orders
                  SET region='east',amount_cents=25000,change_version=2,
                      paid_epoch_ms=UNIX_TIMESTAMP('2026-11-16 16:03:10')*1000
                  WHERE order_id=104;
                INSERT INTO orders_source.orders VALUES
                  (105,'north',7200,'PAID',UNIX_TIMESTAMP('2026-11-17 16:00:30')*1000,1);
            """)
            wait_for("Flink noticed worker loss", lambda: self.rest("/overview")["taskmanagers"] == 0, seconds=40)
        finally:
            self.compose("start", "taskmanager")
        restored = [wait_for("restored checkpoint", lambda: self.checkpoint(job, "restored")) for job in jobs]
        self.assert_current("changes committed while TaskManager was stopped")
        print("PASS checkpoint restore for both jobs:", [item["id"] for item in restored], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep", action="store_true", help="Leave the example project running for inspection")
    parser.add_argument("--reconcile-project", help="Compare ADS tables in an existing Compose example project")
    parser.add_argument("--biz-date", type=date.fromisoformat, help="Business date to compare (YYYY-MM-DD)")
    parser.add_argument("--recompute", action="store_true",
                        help="Run fresh bounded Flink reconciliation for --biz-date")
    args = parser.parse_args()
    if args.recompute and not (args.reconcile_project and args.biz_date):
        parser.error("--recompute requires --reconcile-project and --biz-date")
    if args.reconcile_project:
        if args.keep:
            parser.error("--keep is only used when starting a new example project")
        # Compose resolves all volume substitutions even for an exec into the existing MySQL container.
        if not os.environ.get("DK_CDC_CACHE"):
            os.environ["DK_CDC_CACHE"] = str(Path(tempfile.gettempdir()) / "data-khaos-cdc-3.6.0")
        lab = Lab(args.reconcile_project)
        report = lab.reconcile_batch(args.biz_date) if args.recompute else lab.ads_report(args.biz_date)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1 if report["differences"] else 0
    if args.biz_date:
        parser.error("--biz-date requires --reconcile-project")
    os.environ["DK_CDC_CACHE"] = run(["bash", str(LAB / "prepare.sh")], timeout=1000)
    project = "dk-orders-cdc-" + uuid.uuid4().hex[:8]
    lab = Lab(project)
    print(f"Compose project {project}; cache {os.environ['DK_CDC_CACHE']}", flush=True)
    try:
        lab.setup()
        jobs = lab.submit()
        print(f"Flink REST {lab.rest_url}   jobs {' '.join(jobs)}", flush=True)
        lab.assert_current("initial snapshot")
        initial = lab.records(3)
        if sorted(event["after"]["order_id"] for _, event in initial) != [101, 102, 103]:
            raise RuntimeError(f"Missing initial ODS rows: {initial}")
        lab.mysql("""
            INSERT INTO orders_source.orders VALUES
              (104,'west',20000,'PAID',UNIX_TIMESTAMP('2026-11-16 16:01:01')*1000,1);
            UPDATE orders_source.orders SET region='north',amount_cents=12000,change_version=2 WHERE order_id=101;
            UPDATE orders_source.orders SET status='CANCELLED',change_version=2 WHERE order_id=102;
            DELETE FROM orders_source.orders WHERE order_id=103;
        """)
        lab.assert_current("insert, region move, payment cancellation, delete and late minute")
        incremental = lab.records(7)[3:]
        ops = [(event["op"], (event["after"] or event["before"])["order_id"]) for _, event in incremental]
        if ops != [("c", 104), ("u", 101), ("u", 102), ("d", 103)]:
            raise RuntimeError(f"Unexpected binlog operations: {ops}")
        print("PASS ODS operations:", ops, flush=True)

        lab.recover(jobs)
        recovered = lab.records(10)
        recovered_ops = [(event["op"], (event["after"] or event["before"])["order_id"])
                         for _, event in recovered[7:]]
        if recovered_ops != [("u", 102), ("u", 104), ("c", 105)]:
            raise RuntimeError(f"Unexpected ODS records after restore: {recovered_ops}")
        if any(key["order_id"] != (event["after"] or event["before"])["order_id"] for key, event in recovered):
            raise RuntimeError("ODS records were not keyed by order_id")

        replay = [next(record for record in initial if record[0]["order_id"] == 103),
                  incremental[0], incremental[1]]
        payload = "".join(json.dumps(key) + "\t" + json.dumps(event) + "\n" for key, event in replay)
        lab.kafka("console-producer", "--topic", TOPIC, "--property", "parse.key=true", stdin=payload)
        # A newer sentinel must pass through the same Kafka partition before reading the result.
        lab.mysql("""
            INSERT INTO orders_source.orders VALUES
              (106,'east',1000,'PAID',UNIX_TIMESTAMP('2026-11-16 16:02:02')*1000,1);
        """)
        lab.assert_current("replayed old version and delete tie")
        lab.reconcile_batch(publish=True)
        print("PASS bounded Flink batch and streaming ADS match row by row", flush=True)
        lab.verify_day_recomputation()
        lab.verify_windows()
        print("PASS realtime ADS:\n" + lab.mysql("SELECT * FROM orders_serving.ads_region_day ORDER BY 1,2;"), flush=True)
    except Exception:
        try:
            print(lab.compose("logs", "--tail", "50", "jobmanager", "taskmanager"), flush=True)
        except (RuntimeError, OSError):
            pass
        raise
    finally:
        if args.keep:
            cleanup = shlex.join(["env", f"DK_CDC_CACHE={os.environ['DK_CDC_CACHE']}", *lab.command, "down", "-v"])
            print(f"Kept project {project}. Remove the demo with {cleanup}", flush=True)
        else:
            lab.compose("down", "-v", timeout=90)


if __name__ == "__main__":
    raise SystemExit(main())
