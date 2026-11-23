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
}
