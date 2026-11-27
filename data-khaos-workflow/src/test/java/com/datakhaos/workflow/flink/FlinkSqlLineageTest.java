package com.datakhaos.workflow.flink;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Flink SQL 表级血缘：只验证「源表/目标表识别对不对」，不依赖数据库。 */
class FlinkSqlLineageTest {

    private static final String DDL = """
            CREATE TABLE orders_src (
              order_id BIGINT,
              region STRING,
              amount DECIMAL(18,2),
              paid_at TIMESTAMP(3)
            ) WITH (
              'connector' = 'mysql-cdc',
              'hostname' = 'mysql',
              'database-name' = 'shop',
              'table-name' = 'orders'
            );
            CREATE TABLE region_sink (
              region STRING,
              gmv DECIMAL(18,2)
            ) WITH (
              'connector' = 'jdbc',
              'url' = 'jdbc:mysql://mysql:3306/ads',
              'table-name' = 'ads_region_gmv'
            );
            """;

    @Test
    void parsesInsertIntoWithJdbcSinkAndCdcSource() {
        var result = FlinkSqlLineage.parse(DDL + """
                INSERT INTO region_sink
                SELECT region, SUM(amount) FROM orders_src GROUP BY region;
                """);

        assertEquals("region_sink", result.sink());
        assertEquals("jdbc", result.sinkConnector());
        assertEquals("ads_region_gmv", result.sinkPhysicalName());
        assertEquals(List.of("orders_src"), result.sources().stream().map(FlinkSqlLineage.Source::name).toList());
        var source = result.sources().get(0);
        assertEquals("mysql-cdc", source.connector());
        assertEquals("shop.orders", source.physicalName());
    }

    @Test
    void expandsViewToUnderlyingTables() {
        var result = FlinkSqlLineage.parse(DDL + """
                CREATE TEMPORARY VIEW paid_orders AS
                SELECT region, amount FROM orders_src WHERE amount > 0;
                INSERT INTO region_sink SELECT region, SUM(amount) FROM paid_orders GROUP BY region;
                """);

        assertEquals(List.of("orders_src"), result.sources().stream().map(FlinkSqlLineage.Source::name).toList());
    }

    @Test
    void expandsCteAndIgnoresItsAlias() {
        var result = FlinkSqlLineage.parse(DDL + """
                INSERT INTO region_sink
                WITH paid AS (SELECT region, amount FROM orders_src WHERE amount > 0)
                SELECT region, SUM(amount) FROM paid GROUP BY region;
                """);

        assertEquals(List.of("orders_src"), result.sources().stream().map(FlinkSqlLineage.Source::name).toList());
    }

    @Test
    void handlesCommaJoinAndSubquery() {
        String sql = DDL + """
                CREATE TABLE dim_region (region STRING) WITH ('connector' = 'jdbc', 'table-name' = 'dim_region');
                INSERT INTO region_sink
                SELECT d.region, SUM(o.amount)
                FROM orders_src o, dim_region d
                WHERE o.region = d.region
                  AND o.amount > (SELECT AVG(amount) FROM orders_src)
                GROUP BY d.region;
                """;
        var names = FlinkSqlLineage.parse(sql).sources().stream().map(FlinkSqlLineage.Source::name).toList();

        assertTrue(names.contains("orders_src"));
        assertTrue(names.contains("dim_region"));
        assertEquals(2, names.size());
    }

    @Test
    void ignoresFromInsideFunctionsAndStringLiterals() {
        var result = FlinkSqlLineage.parse(DDL + """
                INSERT INTO region_sink
                SELECT region, SUM(amount)
                FROM orders_src
                WHERE TRIM(BOTH ' ' FROM region) <> 'from fake_table'
                  AND EXTRACT(YEAR FROM paid_at) = 2026
                GROUP BY region;
                """);

        assertEquals(List.of("orders_src"), result.sources().stream().map(FlinkSqlLineage.Source::name).toList());
    }

    @Test
    void readsExplicitAssetHintFromDdl() {
        var result = FlinkSqlLineage.parse("""
                CREATE TABLE sink (
                  region STRING
                ) WITH ('connector' = 'jdbc', 'table-name' = 'ads_region_gmv', 'datakhaos.asset-id' = 'mt_ads_001');
                CREATE TABLE src (region STRING) WITH ('connector' = 'kafka', 'topic' = 'orders');
                INSERT INTO sink SELECT region FROM src;
                """);

        assertEquals("mt_ads_001", result.sinkAssetHint());
        var source = result.sources().get(0);
        assertNull(source.assetHint());
        assertEquals("orders", source.physicalName());
        assertEquals("kafka", source.connector());
    }

    @Test
    void deDuplicatesRepeatedSourceReferences() {
        var result = FlinkSqlLineage.parse(DDL + """
                INSERT INTO region_sink
                SELECT a.region, SUM(a.amount)
                FROM orders_src a
                JOIN orders_src b ON a.order_id = b.order_id
                GROUP BY a.region;
                """);

        assertEquals(1, result.sources().size());
    }

    @Test
    void returnsEmptyWhenThereIsNoInsert() {
        assertTrue(FlinkSqlLineage.parse(DDL).empty());
        assertTrue(FlinkSqlLineage.parse("SELECT 1").empty());
        assertTrue(FlinkSqlLineage.parse(null).empty());
    }
}
