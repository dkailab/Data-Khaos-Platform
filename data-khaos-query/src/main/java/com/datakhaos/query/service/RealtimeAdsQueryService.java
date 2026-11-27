package com.datakhaos.query.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.query.dto.RealtimeReconcile;
import com.datakhaos.query.dto.RealtimeRegionGmv;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static com.datakhaos.common.constant.CommonConstants.HEADER_ROLES;
import static com.datakhaos.common.constant.CommonConstants.HEADER_USER_ID;

/**
 * 实时 ADS 读取与时效判定。
 *
 * <p>与 {@link MaterializationQueryService} 的分工：离线走「业务日分区发布 + 质量快照」证据链，
 * 实时没有分区发布，改用作业自己落库的两个信号判断结果是否可信：
 * <ol>
 *   <li><b>结果观测时间</b> {@code observed_at}：写入该行时由作业按契约时区落库，不是查询时取 now()，
 *       因此可以在库里直接 {@code SELECT MAX(observed_at)} 复核；</li>
 *   <li><b>作业推进信号</b> {@code <table>_events}：按业务日记录已消费的 CDC 事件，
 *       独立于「是否成交」，用来在结果为空时区分<b>确实没有成交</b>与<b>作业尚未推进到该业务日</b>。</li>
 * </ol>
 *
 * <p>契约把时区、金额单位与时效阈值固化在 API 版本上，响应原样回显，调用方无需猜测。
 */
@Service
public class RealtimeAdsQueryService {
    private static final String ASSET_URL = "http://data-khaos-metadata/api/meta/assets/{assetId}";
    private static final String RESTRICTED_URL =
            "http://data-khaos-permission/api/permission/policy/table-restricted?database={db}&table={table}";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern GMV_DECIMAL = Pattern.compile("-?[0-9]{1,14}\\.[0-9]{2}");
    private static final Pattern OBSERVED = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?");
    private static final String EVENT_SUFFIX = "_events";
    private static final String CONTROL_TABLE = "dk_materialization_version";

    public static final String STATE_PRODUCED = "PRODUCED";
    public static final String STATE_EMPTY = "EMPTY";
    public static final String STATE_NOT_YET_PRODUCED = "NOT_YET_PRODUCED";
    /** 推进信号表明分区仍在写入或未过静默窗口：有行也可能是半成品，不能当作 PRODUCED。 */
    public static final String STATE_IN_PROGRESS = "IN_PROGRESS";
    public static final String FRESHNESS_FRESH = "FRESH";
    public static final String FRESHNESS_LAGGING = "LAGGING";
    public static final String FRESHNESS_STALE = "STALE";
    public static final String FRESHNESS_UNKNOWN = "UNKNOWN";

    private final RestTemplate http;
    private final DatasourceApiClient datasources;
    private Clock clock = Clock.systemUTC();

    public RealtimeAdsQueryService(@Qualifier("orchestrationRestTemplate") RestTemplate http,
                                   DatasourceApiClient datasources) {
        this.http = http;
        this.datasources = datasources;
    }

    /** 供测试注入固定时钟，避免时效判定依赖真实时间。 */
    void useClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * 实时契约的固定口径，来自 API 版本配置。
     *
     * @param assetId      实时 ADS 资产
     * @param batchAssetId 对账基线批 ADS 资产，可为空
     * @param timezone     observed_at 的解释时区
     * @param amountUnit   金额单位
     * @param freshSeconds lag 不超过该值视为 FRESH
     * @param staleSeconds lag 超过该值视为 STALE
     * @param maxRows      最大返回地区数
     * @param quietSeconds 分区静默窗口：最后写入距今不足该秒数时结果视为 IN_PROGRESS。0 表示关闭（默认，存量行为不变）
     * @param tolerance    对账容差（0~1），null 或 0 表示 STRICT 精确对账
     * @param mapping      列映射与推进信号表；null 时使用默认地区成交额口径
     */
    public record Contract(String assetId, String batchAssetId, String timezone, String amountUnit,
                           int freshSeconds, int staleSeconds, int maxRows, int quietSeconds,
                           BigDecimal tolerance, Mapping mapping) {

        public Contract {
            if (mapping == null) {
                mapping = Mapping.DEFAULT;
            }
        }

        /** 存量调用：不开启静默窗口、严格对账、默认列映射。 */
        public Contract(String assetId, String batchAssetId, String timezone, String amountUnit,
                        int freshSeconds, int staleSeconds, int maxRows) {
            this(assetId, batchAssetId, timezone, amountUnit, freshSeconds, staleSeconds, maxRows,
                    0, null, Mapping.DEFAULT);
        }

        /** 存量调用：带静默窗口、严格对账、默认列映射。 */
        public Contract(String assetId, String batchAssetId, String timezone, String amountUnit,
                        int freshSeconds, int staleSeconds, int maxRows, int quietSeconds) {
            this(assetId, batchAssetId, timezone, amountUnit, freshSeconds, staleSeconds, maxRows,
                    quietSeconds, null, Mapping.DEFAULT);
        }

        /** 存量调用：无自定义列映射。 */
        public Contract(String assetId, String batchAssetId, String timezone, String amountUnit,
                        int freshSeconds, int staleSeconds, int maxRows, int quietSeconds,
                        BigDecimal tolerance) {
            this(assetId, batchAssetId, timezone, amountUnit, freshSeconds, staleSeconds, maxRows,
                    quietSeconds, tolerance, Mapping.DEFAULT);
        }
    }

    /**
     * 实时契约的列映射：把「地区成交额」之外的实时 ADS 也接进同一响应契约。
     *
     * <p>默认口径保持 {@code region / gmv_cents / paid_order_count / observed_at}，
     * 推进信号默认取 {@code <结果表>_events}。所有字段在构建时归一化，
     * 空值回落默认，避免出现「配置了一半」的契约。</p>
     */
    public record Mapping(String keyColumn, String valueColumn, String countColumn, String observedColumn,
                          String dateColumn, int valueDivisor, String progressTable, String progressDateColumn,
                          String progressAdvancedColumn, String progressCountColumn,
                          String progressEventTimeColumn) {

        public static final Mapping DEFAULT = new Mapping("region", "gmv_cents", "paid_order_count", "observed_at",
                "biz_date", 100, null, "biz_date", "advanced_at", "event_count", "max_event_time");

        public static Mapping of(String keyColumn, String valueColumn, String countColumn, String observedColumn,
                                 String dateColumn, Integer valueDivisor, String progressTable,
                                 String progressDateColumn, String progressAdvancedColumn,
                                 String progressCountColumn, String progressEventTimeColumn) {
            String date = fill(dateColumn, DEFAULT.dateColumn());
            return new Mapping(
                    fill(keyColumn, DEFAULT.keyColumn()), fill(valueColumn, DEFAULT.valueColumn()),
                    fill(countColumn, DEFAULT.countColumn()), fill(observedColumn, DEFAULT.observedColumn()),
                    date,
                    valueDivisor == null || valueDivisor < 1 ? DEFAULT.valueDivisor() : valueDivisor,
                    blankToNull(progressTable),
                    fill(progressDateColumn, date),
                    fill(progressAdvancedColumn, DEFAULT.progressAdvancedColumn()),
                    fill(progressCountColumn, DEFAULT.progressCountColumn()),
                    fill(progressEventTimeColumn, DEFAULT.progressEventTimeColumn()));
        }

        private static String fill(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.strip();
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    public RealtimeRegionGmv regionGmv(Contract contract, String requestedDate) {
        validate(contract);
        LocalDate date = businessDate(requestedDate);
        ZoneId zone = zone(contract);
        Table table = resolve(contract.assetId());
        int limit = contract.maxRows() + 1;
        List<RegionSample> rows = readRegions(table, contract, date, limit);
        EventProgress progress = readEventProgress(table, contract, date);

        boolean truncated = rows.size() > contract.maxRows();
        List<RegionSample> kept = truncated ? rows.subList(0, contract.maxRows()) : rows;
        return judge(contract, date, zone, kept, truncated, progress, clock);
    }

    /**
     * 逐地区对账：实时表与批表在同一业务日、同一契约口径下逐地区比对。
     * 两侧各自附带观测时间，便于判断「数值一致」与「哪一侧更新」。
     */
    public RealtimeReconcile reconcile(Contract contract, String requestedDate) {
        validate(contract);
        if (contract.batchAssetId() == null || contract.batchAssetId().isBlank()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "该 API 版本未配置对账基线批资产");
        }
        LocalDate date = businessDate(requestedDate);
        ZoneId zone = zone(contract);
        Table realtimeTable = resolve(contract.assetId());
        Table batchTable = resolve(contract.batchAssetId());

        List<RegionSample> realtimeRows = readRegions(realtimeTable, contract, date, contract.maxRows() + 1);
        List<RegionSample> batchRows = readRegions(batchTable, contract, date, contract.maxRows() + 1);
        EventProgress progress = readEventProgress(realtimeTable, contract, date);

        boolean realtimeTruncated = realtimeRows.size() > contract.maxRows();
        boolean batchTruncated = batchRows.size() > contract.maxRows();
        if (realtimeTruncated) realtimeRows = realtimeRows.subList(0, contract.maxRows());
        if (batchTruncated) batchRows = batchRows.subList(0, contract.maxRows());

        RealtimeRegionGmv realtime = judge(contract, date, zone, realtimeRows, realtimeTruncated, progress, clock);
        RealtimeRegionGmv batch = judge(contract, date, zone, batchRows, batchTruncated, null, clock);

        BigDecimal tolerance = contract.tolerance();
        boolean tolerant = tolerance != null && tolerance.signum() > 0;
        String mode = tolerant ? RealtimeReconcile.MODE_TOLERANCE : RealtimeReconcile.MODE_STRICT;
        Map<String, RegionSample> left = index(realtimeRows);
        Map<String, RegionSample> right = index(batchRows);
        TreeMap<String, RealtimeReconcile.RegionDiff> diffs = new TreeMap<>();
        for (var entry : left.entrySet()) {
            RegionSample other = right.get(entry.getKey());
            RegionSample mine = entry.getValue();
            if (other == null) {
                diffs.put(entry.getKey(), new RealtimeReconcile.RegionDiff(entry.getKey(), mine.gmv(), null,
                        mine.orderCount(), null, "ONLY_REALTIME"));
                continue;
            }
            diffs.put(entry.getKey(), compare(mine, other, tolerant ? tolerance : null));
        }
        for (var entry : right.entrySet()) {
            if (!left.containsKey(entry.getKey())) {
                RegionSample other = entry.getValue();
                diffs.put(entry.getKey(), new RealtimeReconcile.RegionDiff(entry.getKey(), null, other.gmv(),
                        null, other.orderCount(), "ONLY_BATCH"));
            }
        }
        long accepted = diffs.values().stream()
                .filter(diff -> ACCEPTED.contains(diff.verdict())).count();
        BigDecimal matchRate = diffs.isEmpty() ? BigDecimal.ONE
                : BigDecimal.valueOf(accepted).divide(BigDecimal.valueOf(diffs.size()), 6, RoundingMode.HALF_UP);
        boolean allAccepted = accepted == diffs.size();
        boolean matched = !realtime.truncated() && !batch.truncated()
                && produced(realtime.state()) && produced(batch.state())
                && allAccepted && (!tolerant || matchRate.compareTo(tolerance) >= 0);
        return new RealtimeReconcile(date.toString(), matched, side(realtime), side(batch),
                new ArrayList<>(diffs.values()), mode, tolerant ? tolerance : null, matchRate);
    }

    private static final java.util.Set<String> ACCEPTED =
            java.util.Set.of("MATCHED", "WITHIN_TOLERANCE");

    /** 精确相等直接 MATCHED；容差模式下金额与订单数相对偏差均不超过阈值时记 WITHIN_TOLERANCE。 */
    private static RealtimeReconcile.RegionDiff compare(RegionSample mine, RegionSample other, BigDecimal tolerance) {
        if (mine.gmv().equals(other.gmv()) && mine.orderCount() == other.orderCount()) {
            return new RealtimeReconcile.RegionDiff(mine.region(), mine.gmv(), other.gmv(),
                    mine.orderCount(), other.orderCount(), "MATCHED");
        }
        BigDecimal gmvRate = diffRate(new BigDecimal(mine.gmv()), new BigDecimal(other.gmv()),
                new BigDecimal("0.01"));
        BigDecimal countRate = diffRate(BigDecimal.valueOf(mine.orderCount()),
                BigDecimal.valueOf(other.orderCount()), BigDecimal.ONE);
        String verdict = tolerance != null && gmvRate.compareTo(tolerance) <= 0
                && countRate.compareTo(tolerance) <= 0 ? "WITHIN_TOLERANCE" : "MISMATCH";
        return new RealtimeReconcile.RegionDiff(mine.region(), mine.gmv(), other.gmv(),
                mine.orderCount(), other.orderCount(), verdict, gmvRate, countRate);
    }

    /** 相对偏差 = |左-右| / max(|右|, floor)，floor 避免零分母；保留 6 位小数。 */
    private static BigDecimal diffRate(BigDecimal left, BigDecimal right, BigDecimal floor) {
        BigDecimal denominator = right.abs().max(floor);
        return left.subtract(right).abs().divide(denominator, 6, RoundingMode.HALF_UP);
    }

    /** 只有定稿的结果才能参与对账：写入中（IN_PROGRESS）和尚未产出都不算一致。 */
    private static boolean produced(String state) {
        return STATE_PRODUCED.equals(state) || STATE_EMPTY.equals(state);
    }

    private static RealtimeReconcile.Side side(RealtimeRegionGmv payload) {
        return new RealtimeReconcile.Side(payload.state(), payload.observedAt(), payload.lagSeconds(),
                payload.freshness(), payload.rowCount(), payload.amountUnit(),
                payload.truncated(), payload.processingLagSeconds());
    }

    private static Map<String, RegionSample> index(List<RegionSample> rows) {
        Map<String, RegionSample> index = new LinkedHashMap<>();
        for (RegionSample row : rows) index.put(row.region(), row);
        return index;
    }

    /**
     * 时效判定的纯逻辑，不触达外部依赖，便于覆盖空结果、时钟回拨与阈值边界。
     */
    static RealtimeRegionGmv judge(Contract contract, LocalDate date, ZoneId zone,
                                   List<RegionSample> rows, boolean truncated,
                                   EventProgress progress, Clock clock) {
        LocalDateTime now = LocalDateTime.now(clock.withZone(zone));

        // 处理时钟：作业推进信号的 advanced_at，表示作业「处理到」该业务日的时刻；
        // 与结果行的 observed_at（事件观测时刻）互补：前者反映链路是否堵塞，后者反映数据是否新鲜。
        LocalDateTime advancedAt = progress == null ? null : parseTimestamp(progress.advancedAt());
        Long processingLagSeconds = advancedAt == null ? null : lagSeconds(advancedAt, now);

        // 静默窗口：契约声明 quietSeconds>0 时，作业刚推进不足以证明分区已写完。
        // 即使结果表已有行，也判为 IN_PROGRESS，避免调用方读到半成品的 PRODUCED。
        boolean stillWriting = contract.quietSeconds() > 0 && processingLagSeconds != null
                && processingLagSeconds < contract.quietSeconds();

        String state;
        if (stillWriting) {
            state = STATE_IN_PROGRESS;
        } else if (!rows.isEmpty()) {
            state = STATE_PRODUCED;
        } else if (progress != null && progress.advancedAt() != null) {
            // 作业消费过该业务日的事件，只是没有成交单 → 空数据
            state = STATE_EMPTY;
        } else {
            // 没有任何推进信号 → 尚未产出，不能当作空数据
            state = STATE_NOT_YET_PRODUCED;
        }

        String observedAt = null;
        Long lagSeconds = null;
        String freshness = FRESHNESS_UNKNOWN;
        // IN_PROGRESS 时结果尚未定稿，不给出面向消费的时效结论； observedAt/lag 仍可回显便于诊断。
        if (state.equals(STATE_PRODUCED) || state.equals(STATE_IN_PROGRESS)) {
            LocalDateTime latest = null;
            for (RegionSample row : rows) {
                LocalDateTime candidate = parseTimestamp(row.observedAt());
                if (candidate != null && (latest == null || candidate.isAfter(latest))) latest = candidate;
            }
            if (latest != null) {
                observedAt = formatOffset(latest, zone);
                lagSeconds = lagSeconds(latest, now);
                // 只有定稿的 PRODUCED 才分档；IN_PROGRESS 保留 UNKNOWN，避免误导调用方使用半成品。
                if (state.equals(STATE_PRODUCED)) freshness = classify(lagSeconds, contract);
            }
        }

        RealtimeRegionGmv.Progress signal = progress == null ? null
                : new RealtimeRegionGmv.Progress(
                        progress.advancedAt() == null ? null
                                : formatOffset(parseTimestamp(progress.advancedAt()), zone),
                        processingLagSeconds,
                        progress.eventCount(),
                        progress.maxEventTime() == null ? null
                                : formatOffset(parseTimestamp(progress.maxEventTime()), zone));

        List<RealtimeRegionGmv.RegionRow> regions = rows.stream()
                .map(row -> new RealtimeRegionGmv.RegionRow(row.region(), amount(row.gmv()), row.orderCount(),
                        row.observedAt() == null ? null : formatOffset(parseTimestamp(row.observedAt()), zone)))
                .toList();

        return new RealtimeRegionGmv(date.toString(), RealtimeRegionGmv.MODE, contract.timezone(),
                contract.amountUnit(), state, observedAt, lagSeconds, freshness,
                new RealtimeRegionGmv.FreshnessThresholds(contract.freshSeconds(), contract.staleSeconds()),
                signal, processingLagSeconds, truncated, regions.size(), regions);
    }

    private static String classify(long lagSeconds, Contract contract) {
        if (lagSeconds <= contract.freshSeconds()) return FRESHNESS_FRESH;
        if (lagSeconds <= contract.staleSeconds()) return FRESHNESS_LAGGING;
        return FRESHNESS_STALE;
    }

    /** 时钟回拨或两侧时钟偏差会让 lag 为负，钳制到 0，避免被误判为「未来数据」。 */
    private static long lagSeconds(LocalDateTime observedAt, LocalDateTime now) {
        if (observedAt == null) return 0;
        long seconds = Duration.between(observedAt, now).getSeconds();
        return Math.max(seconds, 0);
    }

    private static String amount(String gmv) {
        if (gmv == null || !GMV_DECIMAL.matcher(gmv).matches()) {
            throw new BusinessException("实时 ADS 金额与契约不匹配（应为两位小数）");
        }
        return gmv;
    }

    private static String formatOffset(LocalDateTime value, ZoneId zone) {
        if (value == null) return null;
        return value.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().replace(' ', 'T');
        if (!OBSERVED.matcher(normalized).matches()) {
            throw new BusinessException("实时 ADS 观测时间格式与契约不匹配");
        }
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw new BusinessException("实时 ADS 观测时间无法解析");
        }
    }

    void validate(Contract contract) {
        if (contract.assetId() == null || !contract.assetId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "实时契约缺少有效的 ADS 资产");
        }
        if (contract.maxRows() < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "最大返回地区数无效");
        }
        if (contract.freshSeconds() < 0 || contract.staleSeconds() <= contract.freshSeconds()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "时效阈值无效（STALE 须大于 FRESH）");
        }
        validate(contract.mapping());
        zone(contract);
    }

    /** 列映射直接拼进 SQL，必须是纯标识符；配置层已校验一次，这里兜底。 */
    private static void validate(Mapping mapping) {
        requireIdentifier(mapping.keyColumn(), "实时契约的地区列");
        requireIdentifier(mapping.valueColumn(), "实时契约的金额列");
        requireIdentifier(mapping.countColumn(), "实时契约的订单数列");
        requireIdentifier(mapping.observedColumn(), "实时契约的观测时间列");
        requireIdentifier(mapping.progressAdvancedColumn(), "实时契约的推进时间列");
        requireIdentifier(mapping.progressCountColumn(), "实时契约的推进事件数列");
        requireIdentifier(mapping.progressEventTimeColumn(), "实时契约的最大事件时间列");
        if (mapping.progressTable() != null) {
            requireIdentifier(mapping.progressTable(), "实时契约的推进信号表");
        }
        requireIdentifier(mapping.dateColumn(), "实时契约的业务日期列");
        requireIdentifier(mapping.progressDateColumn(), "实时契约的推进业务日期列");
        if (mapping.valueDivisor() < 1 || mapping.valueDivisor() > 1_000_000) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "实时契约的金额换算除数无效");
        }
    }

    private static void requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, label + "不是合法的列名");
        }
    }

    private static ZoneId zone(Contract contract) {
        try {
            return ZoneId.of(contract.timezone());
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "契约时区无效");
        }
    }

    private static LocalDate businessDate(String input) {
        if (input != null && input.length() == 10) {
            try {
                LocalDate date = LocalDate.parse(input);
                if (date.getYear() >= 1000) return date;
            } catch (DateTimeParseException ignored) {
                // 统一返回同一个校验错误
            }
        }
        throw new BusinessException(ResultCode.VALIDATE_FAILED, "请选择 YYYY-MM-DD 格式的业务日期");
    }

    // ------------------------------------------------------------------ 外部读取（测试可覆盖）

    /** 解析资产到目标库表；与离线一致走 metadata 资产目录。 */
    Table resolve(String assetId) {
        String owner = MetadataHolder.getUserId();
        if (owner == null || owner.isBlank()) throw new BusinessException(ResultCode.UNAUTHORIZED);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HEADER_USER_ID, owner);
        if (MetadataHolder.isSuperAdmin()) headers.set(HEADER_ROLES, "SUPER_ADMIN");

        R<Detail> body;
        try {
            body = http.exchange(ASSET_URL, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<R<Detail>>() {}, assetId).getBody();
        } catch (RestClientException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "元数据服务暂不可用");
        }
        if (body == null || body.getCode() != 0 || body.getData() == null
                || body.getData().table() == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实时 ADS 资产不存在或不可见");
        }
        AssetTable asset = body.getData().table();
        if (!assetId.equals(asset.id()) || !identifier(asset.databaseName()) || !identifier(asset.tableName())
                || CONTROL_TABLE.equalsIgnoreCase(asset.tableName())
                || asset.datasourceId() == null || asset.datasourceId().isBlank()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "实时 ADS 资产目标不合法");
        }
        if (Boolean.TRUE.equals(restricted(asset, headers))) {
            throw new BusinessException(ResultCode.FORBIDDEN, "目标表存在行列策略，暂无实时契约查询");
        }
        return new Table(asset.datasourceId(), asset.databaseName(), asset.tableName());
    }

    private Boolean restricted(AssetTable asset, HttpHeaders headers) {
        try {
            R<Boolean> body = http.exchange(RESTRICTED_URL, HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<R<Boolean>>() {},
                    asset.databaseName(), asset.tableName()).getBody();
            return body == null || body.getCode() != 0 ? Boolean.TRUE : body.getData();
        } catch (RestClientException e) {
            // 无法确认策略时按存在策略处理，宁可拒绝也不绕过
            return Boolean.TRUE;
        }
    }

    /** 读取结果行；观测时间统一按字符串取回，避免跨服务 JSON 边界的时区漂移。 */
    List<RegionSample> readRegions(Table table, Contract contract, LocalDate date, int limit) {
        List<Map<String, Object>> rows = query(table.datasourceId(), regionSql(table, contract, date, limit)).getRows();
        if (rows == null) return List.of();
        List<RegionSample> samples = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            if (row == null || !(row.get("region") instanceof String region)
                    || !(row.get("gmv") instanceof String gmv)) {
                throw new BusinessException("实时 ADS 数据与契约不匹配");
            }
            samples.add(new RegionSample(region, gmv, count(row.get("order_count")),
                    row.get("observed_at") instanceof String value ? value : null));
        }
        return samples;
    }

    /** 按映射生成结果 SQL；金额统一在 SQL 侧换算成两位小数字符串，Java 只做契约校验。 */
    static String regionSql(Table table, Contract contract, LocalDate date, int limit) {
        Mapping mapping = contract.mapping();
        String gmvExpr = mapping.valueDivisor() == 1
                ? "TRIM(CAST(CAST(%s AS DECIMAL(18,2)) AS CHAR(64)))".formatted(quoted(mapping.valueColumn()))
                : "TRIM(CAST(CAST(%s / %d AS DECIMAL(18,2)) AS CHAR(64)))".formatted(
                        quoted(mapping.valueColumn()), mapping.valueDivisor());
        return """
                SELECT %s AS region,
                       %s AS gmv,
                       %s AS order_count,
                       DATE_FORMAT(%s, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS observed_at
                FROM %s.%s WHERE %s = '%s' ORDER BY %s LIMIT %d
                """.formatted(quoted(mapping.keyColumn()), gmvExpr, quoted(mapping.countColumn()),
                quoted(mapping.observedColumn()), quoted(table.database()), quoted(table.table()),
                quoted(mapping.dateColumn()), date, quoted(mapping.keyColumn()), limit);
    }

    /** 读取作业推进信号：该业务日没有任何行即代表作业尚未推进到这里。 */
    EventProgress readEventProgress(Table table, Contract contract, LocalDate date) {
        List<Map<String, Object>> rows = query(table.datasourceId(), progressSql(table, contract, date)).getRows();
        if (rows == null || rows.isEmpty() || rows.get(0) == null) return null;
        Map<String, Object> row = rows.get(0);
        return new EventProgress(row.get("advanced_at") instanceof String value ? value : null,
                count(row.get("event_count")),
                row.get("max_event_time") instanceof String value ? value : null);
    }

    /** 推进信号默认取结果表名 + _events；配置了 progressTable 时优先使用配置。 */
    static String progressSql(Table table, Contract contract, LocalDate date) {
        Mapping mapping = contract.mapping();
        String progressTable = mapping.progressTable() == null
                ? table.table() + EVENT_SUFFIX : mapping.progressTable();
        return """
                SELECT DATE_FORMAT(%s, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS advanced_at,
                       %s AS event_count,
                       DATE_FORMAT(%s, '%%Y-%%m-%%dT%%H:%%i:%%s.%%f') AS max_event_time
                FROM %s.%s WHERE %s = '%s' LIMIT 1
                """.formatted(quoted(mapping.progressAdvancedColumn()), quoted(mapping.progressCountColumn()),
                quoted(mapping.progressEventTimeColumn()), quoted(table.database()),
                quoted(progressTable), quoted(mapping.progressDateColumn()), date);
    }

    private QueryResult query(String datasourceId, String sql) {
        R<QueryResult> response = datasources.executeRaw(datasourceId, sql);
        if (response == null || response.getCode() != 0 || response.getData() == null) {
            throw new BusinessException("读取实时 ADS 失败，请检查目标库与推进表");
        }
        return response.getData();
    }

    private static long count(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value instanceof String text) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ignored) {
                // 落到统一错误
            }
        }
        throw new BusinessException("实时 ADS 计数与契约不匹配");
    }

    private static boolean identifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static String quoted(String identifier) {
        return "`" + identifier + "`";
    }

    /** 目标表与其派生推进表：{@code <table>_events} 为作业级推进信号，{@code <table>_progress} 供运维交叉核对。 */
    record Table(String datasourceId, String database, String table) {}

    record RegionSample(String region, String gmv, long orderCount, String observedAt) {}

    record EventProgress(String advancedAt, long eventCount, String maxEventTime) {}

    private record Detail(AssetTable table, List<Object> relations) {}

    private record AssetTable(String id, String datasourceId, String databaseName, String tableName) {}
}
