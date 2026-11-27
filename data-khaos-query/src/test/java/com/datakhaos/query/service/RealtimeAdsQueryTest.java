package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.query.dto.RealtimeReconcile;
import com.datakhaos.query.dto.RealtimeRegionGmv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时契约的时效判定与对账。
 *
 * <p>覆盖四件容易出静默错误的事：观测时间缺失时不能当作「新鲜」、滞后必须有明确分档、
 * 空结果不能与「作业尚未推进」混为一谈、时钟回拨不能算成未来数据。
 */
class RealtimeAdsQueryTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
    /** 2026-11-17 00:05:22.345 +08:00 */
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-11-16T16:05:22.345Z"), ZoneOffset.UTC);
    private static final RealtimeAdsQueryService.Contract CONTRACT =
            new RealtimeAdsQueryService.Contract("mt_realtime_ads", "mt_batch_ads", "Asia/Shanghai", "CNY", 60, 300, 100);

    // ---------------------------------------------------------------- 时效判定

    @Test
    void reportsProducedRowsWithObservationTimeAndFreshLag() {
        var payload = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 2, "2026-11-17T00:05:12.345"),
                        new RealtimeAdsQueryService.RegionSample("west", "125.00", 1, "2026-11-17T00:05:10.000")),
                false, progress("2026-11-17T00:05:13.000", 3), NOW);

        assertEquals(RealtimeAdsQueryService.STATE_PRODUCED, payload.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, payload.freshness());
        // 取结果集中最新的观测时间，并带上契约时区偏移，调用方不必猜
        assertEquals("2026-11-17T00:05:12.345+08:00", payload.observedAt());
        assertEquals(10L, payload.lagSeconds());
        assertEquals("REALTIME", payload.dataMode());
        assertEquals("Asia/Shanghai", payload.timezone());
        assertEquals("CNY", payload.amountUnit());
        assertEquals(2, payload.rowCount());
        assertEquals(2, payload.regions().size());
        assertEquals("100.00", payload.regions().get(0).gmv());
        assertEquals(2, payload.regions().get(0).orderCount());
    }

    @Test
    void classifiesLagIntoFreshLaggingAndStaleAtThresholdBoundaries() {
        // 参照时刻 2026-11-17 00:05:22.345：阈值语义为「不超过」
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, freshnessAt("2026-11-17T00:04:22.345"));
        assertEquals(RealtimeAdsQueryService.FRESHNESS_LAGGING, freshnessAt("2026-11-17T00:04:21.345"));
        assertEquals(RealtimeAdsQueryService.FRESHNESS_LAGGING, freshnessAt("2026-11-17T00:00:22.345"));
        assertEquals(RealtimeAdsQueryService.FRESHNESS_STALE, freshnessAt("2026-11-16T23:59:21.345"));
    }

    /** 记录时钟比写入时钟慢时 lag 为负，必须钳制到 0，不能算成「未来数据」。 */
    @Test
    void clampsNegativeLagInsteadOfTreatingFutureRowsAsBroken() {
        var payload = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:09:00.000")),
                false, progress("2026-11-17T00:05:13.000", 1), NOW);

        assertEquals(0L, payload.lagSeconds());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, payload.freshness());
    }

    /** 双时钟：数据观测时刻很新但作业推进很旧，freshness 仍 FRESH，处理滞后单独暴露。 */
    @Test
    void separatesDataFreshnessFromProcessingLag() {
        var payload = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:05:20.000")),
                false, progress("2026-11-16T23:00:00.000", 1), NOW);

        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, payload.freshness());
        assertEquals(2L, payload.lagSeconds());
        // 处理时钟滞后 3922 秒（00:05:22.345 - 23:00:00），反映链路侧而非数据侧
        assertEquals(3922L, payload.processingLagSeconds());
        assertEquals(payload.processingLagSeconds(), payload.progress().lagSeconds());
    }

    // ---------------------------------------------------------------- 空结果语义

    @Test
    void separatesEmptyBusinessDayFromNotYetProduced() {
        // 作业消费过该业务日的事件，只是没有成交单 → 空数据
        var empty = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE, List.of(),
                false, progress("2026-11-17T00:05:13.000", 3), NOW);
        assertEquals(RealtimeAdsQueryService.STATE_EMPTY, empty.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_UNKNOWN, empty.freshness());
        assertNull(empty.observedAt());
        assertEquals(0, empty.rowCount());

        // 没有任何推进信号 → 尚未产出，不能当作空数据
        var pending = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE, List.of(), false, null, NOW);
        assertEquals(RealtimeAdsQueryService.STATE_NOT_YET_PRODUCED, pending.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_UNKNOWN, pending.freshness());
        assertNull(pending.progress());

        // 推进表存在但该业务日没有行，同样按尚未产出处理
        var untouched = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE, List.of(),
                false, progress(null, 0), NOW);
        assertEquals(RealtimeAdsQueryService.STATE_NOT_YET_PRODUCED, untouched.state());
    }

    // ---------------------------------------------------------------- 静默窗口（防半成品）

    /** 开启静默窗口后，作业刚推进就返回 IN_PROGRESS，即使结果表已有行，也不能当成 PRODUCED。 */
    @Test
    void marksFreshlyAdvancedPartitionAsInProgressWhenQuietWindowEnabled() {
        var quiet = new RealtimeAdsQueryService.Contract("mt_realtime_ads", null, "Asia/Shanghai", "CNY", 60, 300, 100, 300);
        var payload = RealtimeAdsQueryService.judge(quiet, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:05:20.500")),
                false, progress("2026-11-17T00:05:13.000", 1), NOW);

        assertEquals(RealtimeAdsQueryService.STATE_IN_PROGRESS, payload.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_UNKNOWN, payload.freshness());
        // 观测时间仍可回显供诊断，但不给出面向消费的时效结论
        assertEquals("2026-11-17T00:05:20.5+08:00", payload.observedAt());
        assertEquals(1, payload.rowCount());
    }

    /** 推进距今不足静默窗口判 IN_PROGRESS；达到或超过窗口即恢复 PRODUCED（边界为「不足」）。 */
    @Test
    void resumesProducedOnceQuietWindowHasElapsed() {
        var quiet = new RealtimeAdsQueryService.Contract("mt_realtime_ads", null, "Asia/Shanghai", "CNY", 60, 300, 100, 60);
        // 推进距今 59 秒 → 不足窗口，仍判写入中
        var inside = RealtimeAdsQueryService.judge(quiet, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:04:23.345")),
                false, progress("2026-11-17T00:04:23.345", 1), NOW);
        assertEquals(RealtimeAdsQueryService.STATE_IN_PROGRESS, inside.state());

        // 推进距今恰好 60 秒 → 已越过窗口，恢复 PRODUCED 并正常分档
        var boundary = RealtimeAdsQueryService.judge(quiet, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:04:22.345")),
                false, progress("2026-11-17T00:04:22.345", 1), NOW);
        assertEquals(RealtimeAdsQueryService.STATE_PRODUCED, boundary.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, boundary.freshness());
    }

    /** 静默窗口默认关闭时，即便作业刚推进也照旧返回 PRODUCED（存量行为不变）。 */
    @Test
    void quietWindowDisabledKeepsLegacyProducedBehavior() {
        var payload = RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, "2026-11-17T00:05:20.000")),
                false, progress("2026-11-17T00:05:13.000", 1), NOW);

        assertEquals(RealtimeAdsQueryService.STATE_PRODUCED, payload.state());
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, payload.freshness());
    }

    // ---------------------------------------------------------------- 读取与截断

    @Test
    void readsRealtimeRowsAndMarksTruncation() {
        var service = service(List.of(
                new RealtimeAdsQueryService.RegionSample("east", "100.00", 2, "2026-11-17T00:05:12.345"),
                new RealtimeAdsQueryService.RegionSample("west", "125.00", 1, "2026-11-17T00:05:12.345")),
                progress("2026-11-17T00:05:13.000", 3));

        var payload = service.regionGmv(contract(1), DATE.toString());

        assertTrue(payload.truncated());
        assertEquals(1, payload.rowCount());
        assertEquals(1, payload.regions().size());
        assertEquals("east", payload.regions().get(0).region());
    }

    @Test
    void rejectsAmountThatIsNotTwoDecimals() {
        var service = service(List.of(
                new RealtimeAdsQueryService.RegionSample("east", "100.5", 2, "2026-11-17T00:05:12.345")),
                progress("2026-11-17T00:05:13.000", 3));

        var error = assertThrows(BusinessException.class, () -> service.regionGmv(contract(100), DATE.toString()));
        assertTrue(error.getMessage().contains("两位小数"));
    }

    // ---------------------------------------------------------------- 对账

    @Test
    void reconcilesRegionByRegionAndReportsEachDifference() {
        var service = new RealtimeAdsQueryService(null, null) {
            @Override
            Table resolve(String assetId) {
                return new Table("ds", "orders_serving", assetId);
            }

            @Override
            List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                return "mt_realtime_ads".equals(table.table())
                        ? List.of(new RegionSample("east", "100.00", 2, "2026-11-17T00:05:12.345"),
                                  new RegionSample("north", "50.00", 1, "2026-11-17T00:05:12.345"))
                        : List.of(new RegionSample("east", "100.00", 2, "2026-11-17T01:00:00.000"),
                                  new RegionSample("west", "125.00", 1, "2026-11-17T01:00:00.000"));
            }

            @Override
            EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                return progress("2026-11-17T00:05:13.000", 3);
            }
        };
        service.useClock(NOW);

        var reconcile = service.reconcile(CONTRACT, DATE.toString());

        assertFalse(reconcile.matched());
        assertEquals("PRODUCED", reconcile.realtime().state());
        assertEquals("PRODUCED", reconcile.batch().state());
        assertEquals("east", reconcile.regions().get(0).region());
        assertEquals("MATCHED", verdict(reconcile, "east"));
        assertEquals("ONLY_REALTIME", verdict(reconcile, "north"));
        assertEquals("ONLY_BATCH", verdict(reconcile, "west"));
    }

    @Test
    void reconciliationReportsMatchedWhenBothSidesAgree() {
        var service = new RealtimeAdsQueryService(null, null) {
            @Override
            Table resolve(String assetId) {
                return new Table("ds", "orders_serving", assetId);
            }

            @Override
            List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                return List.of(new RegionSample("east", "100.00", 2, "2026-11-17T00:05:12.345"));
            }

            @Override
            EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                return progress("2026-11-17T00:05:13.000", 1);
            }
        };
        service.useClock(NOW);

        var reconcile = service.reconcile(CONTRACT, DATE.toString());

        assertTrue(reconcile.matched());
        assertEquals("MATCHED", verdict(reconcile, "east"));
        assertEquals(RealtimeAdsQueryService.FRESHNESS_FRESH, reconcile.realtime().freshness());
        assertEquals(1, reconcile.regions().size());
    }

    @Test
    void cannotReportMatchWhenEitherSideIsTruncated() {
        var first = new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, null);
        var extra = new RealtimeAdsQueryService.RegionSample("west", "200.00", 2, null);
        var other = new RealtimeAdsQueryService.RegionSample("west", "300.00", 2, null);
        for (boolean realtimeTruncated : List.of(false, true)) {
            var left = realtimeTruncated ? List.of(first, extra) : List.of(first);
            var service = new RealtimeAdsQueryService(null, null) {
                @Override
                Table resolve(String assetId) {
                    return new Table("ds", "orders_serving", assetId);
                }

                @Override
                List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                    return "mt_realtime_ads".equals(table.table()) ? left : List.of(first, other);
                }

                @Override
                EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                    return null;
                }
            };

            var reconcile = service.reconcile(contract(1), DATE.toString());

            assertFalse(reconcile.matched());
            assertEquals(realtimeTruncated, reconcile.realtime().truncated());
            assertTrue(reconcile.batch().truncated());
            assertEquals("MATCHED", verdict(reconcile, "east"));
        }
    }

    @Test
    void cannotReportMatchWhenNeitherSideHasProducedData() {
        var reconcile = service(List.of(), null).reconcile(CONTRACT, DATE.toString());

        assertFalse(reconcile.matched());
        assertEquals(RealtimeAdsQueryService.STATE_NOT_YET_PRODUCED, reconcile.realtime().state());
        assertEquals(RealtimeAdsQueryService.STATE_NOT_YET_PRODUCED, reconcile.batch().state());
        assertTrue(reconcile.regions().isEmpty());
    }

    /** 同一组两侧数据：STRICT 判差异，TOLERANCE 在阈值内判一致，且回显模式、阈值与吻合率。 */
    @Test
    void toleranceModeAcceptsSmallDifferencesAndEchoesEvidence() {
        var service = sides("100.00", 20, "95.00", 20);
        var strict = service.reconcile(CONTRACT, DATE.toString());
        assertFalse(strict.matched());
        assertEquals(RealtimeReconcile.MODE_STRICT, strict.mode());
        assertNull(strict.tolerance());
        assertEquals("MISMATCH", verdict(strict, "east"));

        var tolerant = service.reconcile(withTolerance(new BigDecimal("0.06")), DATE.toString());
        assertTrue(tolerant.matched());
        assertEquals(RealtimeReconcile.MODE_TOLERANCE, tolerant.mode());
        assertEquals(0, new BigDecimal("0.06").compareTo(tolerant.tolerance()));
        assertEquals("WITHIN_TOLERANCE", verdict(tolerant, "east"));
        assertEquals(0, BigDecimal.ONE.compareTo(tolerant.matchRate()));
        // 相对偏差以批侧为分母：|100-95| / 95 = 0.052632
        assertEquals(0, new BigDecimal("0.052632").compareTo(tolerant.regions().get(0).gmvDiffRate()));
    }

    /** 超出容差、订单数偏差过大、或单侧缺地区时都不能判一致。 */
    @Test
    void toleranceModeStillRejectsOutOfRangeAndMissingRegions() {
        var service = sides("100.00", 20, "95.00", 20);
        assertFalse(service.reconcile(withTolerance(new BigDecimal("0.04")), DATE.toString()).matched());

        var countDrift = sides("100.00", 20, "100.00", 10);
        var countResult = countDrift.reconcile(withTolerance(new BigDecimal("0.06")), DATE.toString());
        assertFalse(countResult.matched());
        assertEquals("MISMATCH", verdict(countResult, "east"));

        var missing = new RealtimeAdsQueryService(null, null) {
            @Override
            Table resolve(String assetId) { return new Table("ds", "orders_serving", assetId); }

            @Override
            List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                return "mt_realtime_ads".equals(table.table())
                        ? List.of(new RegionSample("east", "100.00", 20, "2026-11-17T00:05:12.345"))
                        : List.of(new RegionSample("west", "100.00", 20, "2026-11-17T00:05:12.345"));
            }

            @Override
            EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                return progress("2026-11-17T00:05:13.000", 20);
            }
        };
        missing.useClock(NOW);
        var missingResult = missing.reconcile(withTolerance(new BigDecimal("0.50")), DATE.toString());
        assertFalse(missingResult.matched());
        // 两个地区都只在单侧出现，吻合率为 0
        assertEquals(0, BigDecimal.ZERO.compareTo(missingResult.matchRate()));
    }

    @Test
    void reconciliationRequiresAConfiguredBatchBaseline() {
        var service = service(List.of(), null);
        var withoutBatch = new RealtimeAdsQueryService.Contract("mt_realtime_ads", null, "Asia/Shanghai", "CNY", 60, 300, 100);

        var error = assertThrows(BusinessException.class, () -> service.reconcile(withoutBatch, DATE.toString()));
        assertTrue(error.getMessage().contains("对账基线"));
    }

    // ---------------------------------------------------------------- 契约校验

    @Test
    void rejectsInvalidThresholdsAndTimezone() {
        var badThreshold = new RealtimeAdsQueryService.Contract("mt_realtime_ads", null, "Asia/Shanghai", "CNY", 300, 60, 100);
        var badZone = new RealtimeAdsQueryService.Contract("mt_realtime_ads", null, "Mars/Olympus", "CNY", 60, 300, 100);
        var service = service(List.of(), null);

        assertThrows(BusinessException.class, () -> service.regionGmv(badThreshold, DATE.toString()));
        assertThrows(BusinessException.class, () -> service.regionGmv(badZone, DATE.toString()));
        assertThrows(BusinessException.class, () -> service.regionGmv(contract(100), "2026-13-40"));
    }

    // ---------------------------------------------------------------- helpers

    private static String freshnessAt(String observedAt) {
        return RealtimeAdsQueryService.judge(CONTRACT, DATE, ZONE,
                List.of(new RealtimeAdsQueryService.RegionSample("east", "100.00", 1, observedAt)),
                false, progress("2026-11-17T00:05:13.000", 1), NOW).freshness();
    }

    private static RealtimeAdsQueryService.EventProgress progress(String advancedAt, long events) {
        return new RealtimeAdsQueryService.EventProgress(advancedAt, events, "2026-11-17T00:02:02.000");
    }

    private static String verdict(RealtimeReconcile reconcile, String region) {
        return reconcile.regions().stream().filter(diff -> region.equals(diff.region()))
                .findFirst().orElseThrow().verdict();
    }

    private static RealtimeAdsQueryService.Contract contract(int maxRows) {
        return new RealtimeAdsQueryService.Contract("mt_realtime_ads", "mt_batch_ads",
                "Asia/Shanghai", "CNY", 60, 300, maxRows);
    }

    private static RealtimeAdsQueryService.Contract withTolerance(BigDecimal tolerance) {
        return new RealtimeAdsQueryService.Contract("mt_realtime_ads", "mt_batch_ads",
                "Asia/Shanghai", "CNY", 60, 300, 100, 0, tolerance);
    }

    // ---------------------------------------------------------------- 通用列映射

    /** 默认口径不变：存量版本仍读 region/gmv_cents/paid_order_count 与 <表>_events。 */
    @Test
    void defaultMappingKeepsRegionGmvContract() {
        String sql = RealtimeAdsQueryService.regionSql(new RealtimeAdsQueryService.Table("ds", "analytics", "orders_serving"),
                CONTRACT, DATE, 101);

        assertTrue(sql.contains("`region` AS region"));
        assertTrue(sql.contains("`gmv_cents` / 100"));
        assertTrue(sql.contains("`paid_order_count` AS order_count"));
        assertTrue(sql.contains("ORDER BY `region` LIMIT 101"));
        String progress = RealtimeAdsQueryService.progressSql(
                new RealtimeAdsQueryService.Table("ds", "analytics", "orders_serving"), CONTRACT, DATE);
        assertTrue(progress.contains("`orders_serving_events`"));
    }

    /** 换一套列名和推进表后，SQL 完全由配置驱动，不需要改 Java。 */
    @Test
    void buildsSqlFromConfiguredColumnsAndProgressTable() {
        var contract = new RealtimeAdsQueryService.Contract("asset", null, "Asia/Shanghai", "CNY", 60, 300, 100,
                0, null, RealtimeAdsQueryService.Mapping.of("city", "amount_yuan", "order_cnt", "seen_at",
                        "stat_date", 1, "ads_city_progress", "stat_date", "advanced_at", "cnt", "max_ts"));
        String sql = RealtimeAdsQueryService.regionSql(
                new RealtimeAdsQueryService.Table("ds", "analytics", "ads_city_day"), contract, DATE, 50);

        assertTrue(sql.contains("`city` AS region"));
        assertTrue(sql.contains("`amount_yuan` AS DECIMAL(18,2)"));
        assertFalse(sql.contains("/ 100"));
        assertTrue(sql.contains("`order_cnt` AS order_count"));
        assertTrue(sql.contains("`seen_at`"));
        assertTrue(sql.contains("ORDER BY `city` LIMIT 50"));

        String progress = RealtimeAdsQueryService.progressSql(
                new RealtimeAdsQueryService.Table("ds", "analytics", "ads_city_day"), contract, DATE);
        assertTrue(progress.contains("`ads_city_progress`"));
        assertTrue(progress.contains("`cnt` AS event_count"));
        assertTrue(progress.contains("`max_ts`"));
    }

    /** 部分字段留空时按字段回落默认，不产生「配置了一半」的契约。 */
    @Test
    void partiallyConfiguredMappingFallsBackPerField() {
        var mapping = RealtimeAdsQueryService.Mapping.of("city", null, null, null, null, null,
                null, null, null, null, null);

        assertEquals("city", mapping.keyColumn());
        assertEquals("gmv_cents", mapping.valueColumn());
        assertEquals("paid_order_count", mapping.countColumn());
        assertEquals("observed_at", mapping.observedColumn());
        assertEquals(100, mapping.valueDivisor());
        assertNull(mapping.progressTable());
        assertEquals("advanced_at", mapping.progressAdvancedColumn());
    }

    /** 列名要直接拼进 SQL，非法标识符必须在调用前拒绝。 */
    @Test
    void rejectsIllegalMappingIdentifiers() {
        var service = new RealtimeAdsQueryService(null, null);
        var illegal = new RealtimeAdsQueryService.Contract("asset", null, "Asia/Shanghai", "CNY", 60, 300, 100,
                0, null, RealtimeAdsQueryService.Mapping.of("region); DROP TABLE x; --", null, null, null,
                        null, null, null, null, null, null, null));

        assertThrows(BusinessException.class, () -> service.validate(illegal));
    }

    /** 构造一个实时侧与批侧金额/订单数不同的服务，用于验证容差判定。 */
    private static RealtimeAdsQueryService sides(String realtimeGmv, long realtimeCount,
                                                 String batchGmv, long batchCount) {
        var service = new RealtimeAdsQueryService(null, null) {
            @Override
            Table resolve(String assetId) { return new Table("ds", "orders_serving", assetId); }

            @Override
            List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                return "mt_realtime_ads".equals(table.table())
                        ? List.of(new RegionSample("east", realtimeGmv, realtimeCount, "2026-11-17T00:05:12.345"))
                        : List.of(new RegionSample("east", batchGmv, batchCount, "2026-11-17T00:05:12.345"));
            }

            @Override
            EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                return progress("2026-11-17T00:05:13.000", realtimeCount);
            }
        };
        service.useClock(NOW);
        return service;
    }

    private static RealtimeAdsQueryService service(List<RealtimeAdsQueryService.RegionSample> rows,
                                                   RealtimeAdsQueryService.EventProgress progress) {
        var service = new RealtimeAdsQueryService(null, null) {
            @Override
            Table resolve(String assetId) {
                return new Table("ds", "orders_serving", "ads_region_day");
            }

            @Override
            List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
                return rows.size() > limit ? rows.subList(0, limit) : rows;
            }

            @Override
            EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
                return progress;
            }
        };
        service.useClock(NOW);
        return service;
    }
}
