import io
import json
import sys
import unittest
from datetime import date
from unittest.mock import patch

from verify import Lab, business_day_bounds, main


class ReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.lab = Lab("test")
        self.lab.rest_url = "http://localhost:8081"
        self.day = date(2026, 11, 17)
        self.statements = []
        self.scripts = []
        self.rows = []
        self.job_name = None

        def mysql(sql, **kwargs):
            self.statements.append(sql)
            return "\n".join(json.dumps(row) for row in self.rows) if kwargs.get("raw") else ""

        def compose(*args, stdin, timeout):
            self.scripts.append(stdin)
            self.job_name = stdin.splitlines()[0].split("'")[3]
            return ""

        for method, replacement in (("mysql", mysql), ("compose", compose)):
            patcher = patch.object(self.lab, method, side_effect=replacement)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = patch.object(self.lab, "rest", side_effect=lambda _: {
            "jobs": [{"name": self.job_name, "state": "FINISHED"}],
        })
        patcher.start()
        self.addCleanup(patcher.stop)

    def matched_rows(self):
        return [["stream", self.day.isoformat(), "east", 1, "2500"],
                ["batch", self.day.isoformat(), "east", 1, "2500"]]

    def test_business_day_uses_shanghai_midnight(self):
        start, end = business_day_bounds(self.day)
        self.assertEqual(start, 1794844800000)
        self.assertEqual(end - start, 86_399_999)

    def test_day_recomputation_is_isolated_and_includes_final_millisecond(self):
        self.rows = self.matched_rows()
        self.assertEqual(self.lab.reconcile_batch(self.day)["differences"], [])
        start, end = business_day_bounds(self.day)
        run_id = self.job_name.split()[-1]
        self.assertIn(f"paid_epoch_ms BETWEEN {start} AND {end}", self.statements[0])
        self.assertIn(f"batch_snapshot_{run_id}", self.scripts[0])
        self.assertIn(f"batch_ads_{run_id}", self.scripts[0])
        self.assertEqual(self.statements[1].count("WHERE biz_date = '2026-11-17'"), 2)
        self.assertIn(f"DROP TABLE IF EXISTS orders_serving.batch_ads_{run_id}", self.statements[2])
        self.assertEqual(len(self.statements), 3)

    def test_repeated_recomputation_uses_distinct_tables(self):
        self.rows = self.matched_rows()
        self.lab.reconcile_batch(self.day)
        first_job = self.job_name
        self.lab.reconcile_batch(self.day)
        self.assertNotEqual(first_job, self.job_name)
        self.assertNotIn(first_job.split()[-1], self.scripts[1])

    def test_cli_recompute_compares_the_requested_day(self):
        self.rows = self.matched_rows()
        self.rows[1][-1] = "2600"
        output = io.StringIO()
        with patch("verify.Lab", return_value=self.lab), patch.object(sys, "argv", [
            "verify.py", "--reconcile-project", "test", "--biz-date", self.day.isoformat(), "--recompute",
        ]), patch.object(sys, "stdout", output), patch.dict("os.environ", {"DK_CDC_CACHE": "/tmp/test"}):
            self.assertEqual(main(), 1)
        result = json.loads(output.getvalue())
        self.assertEqual(result["differences"][0]["batch"]["gmv_cents"], 2600)
        self.assertEqual(len(self.scripts), 1)

    def test_matching_full_reconciliation_publishes_in_one_transaction(self):
        self.rows = self.matched_rows()
        self.lab.reconcile_batch(publish=True)
        publication = self.statements[2]
        self.assertIn("START TRANSACTION;", publication)
        self.assertIn("DELETE FROM orders_serving.ads_region_day_batch;", publication)
        self.assertIn("INSERT INTO orders_serving.ads_region_day_batch", publication)
        self.assertIn("COMMIT;", publication)

    def test_mismatch_preserves_published_rows_and_cleans_up(self):
        self.rows = self.matched_rows()
        self.rows[1][-1] = "2600"
        with self.assertRaisesRegex(RuntimeError, "value_mismatch"):
            self.lab.reconcile_batch(publish=True)
        self.assertEqual(len(self.statements), 3)
        self.assertIn("DROP TABLE IF EXISTS", self.statements[-1])

    def test_failed_flink_job_does_not_publish_partial_rows(self):
        with patch.object(self.lab, "rest", side_effect=lambda _: {
            "jobs": [{"name": self.job_name, "state": "FAILED"}],
        }):
            with self.assertRaisesRegex(RuntimeError, "FAILED"):
                self.lab.reconcile_batch(publish=True)
        self.assertEqual(len(self.statements), 2)
        self.assertIn("DROP TABLE IF EXISTS", self.statements[-1])

    def test_submit_failure_cleans_up_without_publishing(self):
        with patch.object(self.lab, "submit_sql", side_effect=RuntimeError("job failed")):
            with self.assertRaisesRegex(RuntimeError, "job failed"):
                self.lab.reconcile_batch(publish=True)
        self.assertEqual(len(self.statements), 2)
        self.assertIn("DROP TABLE IF EXISTS", self.statements[-1])


if __name__ == "__main__":
    unittest.main()
