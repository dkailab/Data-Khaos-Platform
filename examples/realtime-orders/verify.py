#!/usr/bin/env python3
"""Run the isolated MySQL–Kafka–Flink acceptance scenario."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

LAB = Path(__file__).resolve().parent
TOPIC = "orders.ods.v1"
FLINK_SUBMIT_TIMEOUT = 360


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
        except (OSError, RuntimeError, KeyError, ValueError, subprocess.TimeoutExpired) as error:
            last = error
        time.sleep(1)
    raise RuntimeError(f"Timed out: {description}. Last error: {last}")


class Lab:
    def __init__(self, project):
        self.command = ["docker", "compose", "-p", project, "-f", str(LAB / "compose.yml")]
        self.rest_url = None

    def compose(self, *args, stdin=None, timeout=45):
        return run([*self.command, *args], stdin=stdin, timeout=timeout)

    def mysql(self, sql):
        return self.compose("exec", "-T", "mysql", "sh", "-ec",
                            'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql --protocol=TCP -h127.0.0.1 --connect-timeout=5 -uroot -NB',
                            stdin="SET time_zone = '+00:00';\n" + sql)

    def kafka(self, tool, *args, stdin=None):
        return self.compose("exec", "-T", "kafka", f"/opt/kafka/bin/kafka-{tool}.sh",
                            "--bootstrap-server", "kafka:9092", *args, stdin=stdin, timeout=120)

    def rest(self, path):
        with urllib.request.urlopen(self.rest_url + path, timeout=5) as response:
            return json.load(response)

    def setup(self):
        self.compose("up", "-d", "--no-build", timeout=400)
        # The entrypoint's temporary server does not expose TCP during initialization.
        wait_for("MySQL schema and fixture",
                 lambda: self.mysql("SELECT COUNT(*) FROM orders_source.orders;") == "3", seconds=300)
        wait_for("Kafka broker", lambda: self.kafka("topics", "--create", "--if-not-exists",
                                                   "--topic", TOPIC, "--partitions", "1",
                                                   "--replication-factor", "1",
                                                   "--config", "cleanup.policy=delete") is not None, seconds=250)
        self.rest_url = "http://" + self.compose("port", "jobmanager", "8081")
        wait_for("Flink task slots", lambda: self.rest("/overview")["slots-total"] >= 2)

    def submit(self):
        self.compose("exec", "-T", "jobmanager", "env", "FLINK_HOME=/opt/flink", "bash",
                     "/cdc/bin/flink-cdc.sh", "/lab/orders-cdc.yaml", timeout=FLINK_SUBMIT_TIMEOUT)
        cdc = self.job("Orders MySQL CDC")
        self.submit_sql("warehouse.sql")
        warehouse = self.job("Orders realtime warehouse")
        return [cdc, warehouse]

    def submit_sql(self, filename):
        script = (LAB / filename).read_text(encoding="utf-8")
        shared = (LAB / "paid-order-views.sql").read_text(encoding="utf-8")
        script = script.replace("-- INCLUDE paid-order-views.sql", shared.rstrip())
        return self.compose("exec", "-T", "jobmanager", "sh", "-ec",
                     """input=$(mktemp); trap 'rm -f "$input"' EXIT; cat > "$input"; /opt/flink/bin/sql-client.sh -f "$input" """,
                     stdin=script, timeout=FLINK_SUBMIT_TIMEOUT)

    def reconcile_batch(self):
        self.mysql("""
            START TRANSACTION;
            DELETE FROM orders_serving.batch_order_snapshot;
            INSERT INTO orders_serving.batch_order_snapshot SELECT * FROM orders_source.orders;
            DELETE FROM orders_serving.ads_region_day_batch;
            COMMIT;
        """)
        self.submit_sql("batch-reconcile.sql")
        wait_for("bounded Flink job finished", lambda: any(
            job["name"] == "Orders bounded reconciliation" and job["state"] == "FINISHED"
            for job in self.rest("/jobs/overview")["jobs"]))
        columns = "biz_date,region,paid_order_count,gmv_cents"
        stream = self.result(f"SELECT {columns} FROM orders_serving.ads_region_day ORDER BY 1,2;")
        batch = self.result(f"SELECT {columns} FROM orders_serving.ads_region_day_batch ORDER BY 1,2;")
        if not batch or stream != batch:
            raise RuntimeError(f"Flink batch/stream mismatch: stream={stream}, batch={batch}")
        print("PASS bounded Flink batch and streaming ADS match row by row", flush=True)

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

    def assert_freshness(self, label):
        """观测时间与推进信号必须由作业落库，且推进信号要和表内容一致。"""
        missing = self.result(
            "SELECT COUNT(*) FROM orders_serving.ads_region_day WHERE observed_at IS NULL;")
        if missing != ["0"]:
            raise RuntimeError(f"{label}: ads_region_day has NULL observed_at ({missing})")
        # 观测时间应落在上海时区当前时刻附近（±1 天），既能发现未写入，也不绑死时区解释。
        drift = self.result("""
            SELECT COUNT(*) FROM orders_serving.ads_region_day
            WHERE ABS(TIMESTAMPDIFF(SECOND, observed_at,
                    CONVERT_TZ(NOW(), '+00:00', '+08:00'))) > 86400;
        """)
        if drift != ["0"]:
            raise RuntimeError(f"{label}: observed_at is not near current time ({drift})")
        mismatch = self.result("""
            SELECT p.biz_date, p.region_count, COUNT(a.region)
            FROM orders_serving.ads_region_day_progress p
            LEFT JOIN orders_serving.ads_region_day a ON a.biz_date = p.biz_date
            GROUP BY p.biz_date, p.region_count
            HAVING p.region_count <> COUNT(a.region);
        """)
        if mismatch:
            raise RuntimeError(f"{label}: progress disagrees with ADS rows: {mismatch}")
        stalled = self.result(
            "SELECT COUNT(*) FROM orders_serving.ads_region_day_events WHERE event_count <= 0;")
        if stalled != ["0"]:
            raise RuntimeError(f"{label}: event progress never advanced ({stalled})")
        empty = self.result("SELECT COUNT(*) FROM orders_serving.ads_region_day_events;")
        if empty == ["0"]:
            raise RuntimeError(f"{label}: no event progress rows, job advance signal missing")
        print(f"PASS {label}: observed_at and progress signals verified", flush=True)

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
        self.assert_freshness("after checkpoint restore")
        print("PASS checkpoint restore for both jobs:", [item["id"] for item in restored], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--keep", action="store_true", help="Leave the example project running for inspection")
    parser.add_argument("--smoke", action="store_true",
                        help="Verify snapshot and incremental CDC against DWD, DWS and ADS only")
    args = parser.parse_args()
    os.environ["DK_CDC_CACHE"] = run(["bash", str(LAB / "prepare.sh")], timeout=1000)
    project = "dk-orders-cdc-" + uuid.uuid4().hex[:8]
    lab = Lab(project)
    print(f"Compose project {project}; cache {os.environ['DK_CDC_CACHE']}", flush=True)
    try:
        lab.setup()
        if not args.smoke:
            # Run the independent window fixture before the long-running jobs share the TaskManager.
            lab.verify_windows()
        jobs = lab.submit()
        print(f"Flink REST {lab.rest_url}   jobs {' '.join(jobs)}", flush=True)
        lab.assert_current("initial snapshot")
        lab.assert_freshness("initial snapshot")
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
        lab.assert_freshness("incremental changes")
        incremental = lab.records(7)[3:]
        ops = [(event["op"], (event["after"] or event["before"])["order_id"]) for _, event in incremental]
        if ops != [("c", 104), ("u", 101), ("u", 102), ("d", 103)]:
            raise RuntimeError(f"Unexpected binlog operations: {ops}")
        print("PASS ODS operations:", ops, flush=True)

        if args.smoke:
            print("PASS smoke: snapshot and incremental CDC reconciled through ADS", flush=True)
            return

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
        lab.reconcile_batch()
        print("PASS realtime ADS:\n" + lab.mysql("SELECT * FROM orders_serving.ads_region_day ORDER BY 1,2;"), flush=True)
    except Exception as error:
        if isinstance(error, subprocess.TimeoutExpired):
            for output in (error.stdout, error.stderr):
                if output:
                    print(output.decode(errors="replace"), flush=True)
        try:
            print(lab.compose("logs", "--tail", "50", "mysql", "kafka", "jobmanager", "taskmanager"), flush=True)
        except (RuntimeError, OSError, subprocess.TimeoutExpired):
            pass
        raise
    finally:
        if args.keep:
            print(f"Kept project {project}. Remove the demo with docker compose -p {project} "
                  f"-f {LAB / 'compose.yml'} down -v", flush=True)
        else:
            lab.compose("down", "-v", timeout=90)


if __name__ == "__main__":
    main()
