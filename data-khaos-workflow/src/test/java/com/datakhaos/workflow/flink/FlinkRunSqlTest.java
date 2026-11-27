package com.datakhaos.workflow.flink;

import com.datakhaos.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlinkRunSqlTest {
    @Test
    void preservesQuotedContentAndSplitsOnlyTopLevelSemicolons() {
        var parsed = FlinkRunSql.parse("""
                -- seed; comment
                SET 'execution.runtime-mode' = 'batch';
                CREATE TEMPORARY TABLE `sink;name` (content STRING) WITH ('connector' = 'blackhole');
                /* before insert; */ INSERT INTO `sink;name` VALUES ('quoted ''value''; -- literal');
                """);
        assertEquals(2, parsed.setup().size());
        assertEquals("INSERT INTO `sink;name` VALUES ('quoted ''value''; -- literal')", parsed.insert());
    }

    @Test
    void rejectsExtraWritesAndChangesToManagedSettings() {
        assertThrows(BusinessException.class,
                () -> FlinkRunSql.parse("INSERT INTO one VALUES (1); INSERT INTO two VALUES (2);"));
        assertThrows(BusinessException.class,
                () -> FlinkRunSql.parse("SET 'pipeline.name' = 'other'; INSERT INTO one VALUES (1)"));
        assertThrows(BusinessException.class, () -> FlinkRunSql.parse(
                "SET 'execution.runtime-mode'='batch'; SET 'execution.runtime-mode'='streaming'; INSERT INTO one VALUES (1)"));
        assertThrows(BusinessException.class, () -> FlinkRunSql.parse("INSERT INTO one VALUES ('unterminated);"));
    }

    @Test
    void validatesSelectedQueriesAndTheirTemporaryContext() {
        var preview = FlinkRunSql.parseSelection("""
                -- The editor selection may include its temporary definitions.
                CREATE TEMPORARY VIEW order_sample AS SELECT 1 AS id;
                SELECT id, 'a;b' AS label FROM order_sample;
                """);
        assertEquals(1, preview.setup().size());
        assertEquals("SELECT id, 'a;b' AS label FROM order_sample", preview.query());
        assertThrows(BusinessException.class, () -> FlinkRunSql.parseSelection("INSERT INTO sink VALUES (1)"));
        assertThrows(BusinessException.class, () -> FlinkRunSql.parseSelection("DROP TABLE db.orders; SELECT 1"));
        assertThrows(BusinessException.class, () -> FlinkRunSql.parseSelection("SET 'execution.runtime-mode' = 'streaming'; SELECT 1"));
        assertThrows(BusinessException.class, () -> FlinkRunSql.parseSelection("CREATE TEMPORARY FUNCTION external AS 'pkg.Class'; SELECT 1"));
    }

}
