package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.approval.api.service.ApprovalApiClient;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.dto.OrderRegionGmv;
import com.datakhaos.query.dto.RealtimeReconcile;
import com.datakhaos.query.dto.WarehouseApiDraft;
import com.datakhaos.query.entity.WarehouseApiVersion;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.regex.Pattern;

import static com.datakhaos.query.entity.WarehouseApiVersion.DataMode.BATCH;
import static com.datakhaos.query.entity.WarehouseApiVersion.DataMode.REALTIME;
import static com.datakhaos.query.entity.WarehouseApiVersion.Status.DRAFT;
import static com.datakhaos.query.entity.WarehouseApiVersion.Status.OFFLINE;
import static com.datakhaos.query.entity.WarehouseApiVersion.Status.PUBLISHED;

@Service
@RequiredArgsConstructor
public class WarehouseApiService {
    private static final Pattern PROJECT_ID = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final Pattern API_CODE = Pattern.compile("[a-z][a-z0-9-]{0,47}");
    private static final Pattern ASSET_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** 实时契约的列名/表名只接受纯标识符，避免把配置拼进 SQL 时被注入。 */
    private static final Pattern COLUMN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final int MAX_VALUE_DIVISOR = 1_000_000;
    private static final String DEVELOP = "task:develop";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final String DEFAULT_UNIT = "CNY";
    private static final int DEFAULT_FRESH_SECONDS = 60;
    private static final int DEFAULT_STALE_SECONDS = 300;
    private static final int MAX_THRESHOLD_SECONDS = 86_400;
    private static final int MAX_QUIET_SECONDS = 3_600;

    private final WarehouseApiVersionMapper mapper;
    private final PermissionApiClient permissions;
    private final MaterializationQueryService materializations;
    private final RealtimeAdsQueryService realtime;

    /** 发布是否需要审批通过；默认关闭，避免影响既有发布流程。 */
    @Value("${data-khaos.warehouse.approval-required:false}")
    private boolean approvalRequired;

    @Autowired(required = false)
    private ApprovalApiClient approvals;

    public PageResult<WarehouseApiVersion> list(String projectId, long current, long size) {
        requireProject(projectId, true);
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        var result = mapper.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiVersion>lambdaQuery()
                .eq(WarehouseApiVersion::getProjectId, projectId)
                .orderByAsc(WarehouseApiVersion::getApiCode)
                .orderByDesc(WarehouseApiVersion::getApiVersion));
        return PageResult.of(current, size, result.getTotal(), result.getRecords());
    }

    public WarehouseApiVersion create(String projectId, String code, int version, WarehouseApiDraft body) {
        String user = requireProject(projectId, true);
        validateKey(code, version);
        Spec spec = spec(body);
        var draft = new WarehouseApiVersion();
        draft.setProjectId(projectId);
        draft.setApiCode(code);
        draft.setApiVersion(version);
        draft.setApiName(spec.name());
        draft.setMaxRows(spec.maxRows());
        draft.setDataMode(spec.mode());
        draft.setRealtimeAssetId(spec.realtimeAssetId());
        draft.setRealtimeBatchAssetId(spec.batchAssetId());
        draft.setObservedTimezone(spec.timezone());
        draft.setAmountUnit(spec.amountUnit());
        draft.setFreshnessFreshSeconds(spec.freshSeconds());
        draft.setFreshnessStaleSeconds(spec.staleSeconds());
        draft.setQuietSeconds(spec.quietSeconds());
        draft.setReconcileTolerance(spec.tolerance());
        setMapping(draft, spec.mapping());
        draft.setStatus(DRAFT);
        draft.setRevision(1);
        var now = LocalDateTime.now().withNano(0);
        draft.setCreatedBy(user);
        draft.setCreateTime(now);
        draft.setUpdatedBy(user);
        draft.setUpdateTime(now);
        try {
            mapper.insert(draft);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "此项目已使用该 API 版本");
        }
        return draft;
    }

    @Transactional
    public WarehouseApiVersion edit(String projectId, String code, int version, int revision, WarehouseApiDraft body) {
        String user = requireProject(projectId, true);
        var draft = find(projectId, code, version);
        if (body == null) throw new BusinessException(ResultCode.VALIDATE_FAILED, "缺少草稿内容");
        var changes = update(draft, DRAFT, revision, user, LocalDateTime.now().withNano(0))
                .set(WarehouseApiVersion::getApiName, validateDraft(body.name(), body.maxRows()))
                .set(WarehouseApiVersion::getMaxRows, body.maxRows());
        // 省略 dataMode 时只更新名称与行数上限，保留既有契约；显式提供时整体替换（含静默窗口）。
        if (body.dataMode() != null) {
            Spec spec = spec(body);
            changes.set(WarehouseApiVersion::getDataMode, spec.mode())
                    .set(WarehouseApiVersion::getRealtimeAssetId, spec.realtimeAssetId())
                    .set(WarehouseApiVersion::getRealtimeBatchAssetId, spec.batchAssetId())
                    .set(WarehouseApiVersion::getObservedTimezone, spec.timezone())
                    .set(WarehouseApiVersion::getAmountUnit, spec.amountUnit())
                    .set(WarehouseApiVersion::getFreshnessFreshSeconds, spec.freshSeconds())
                    .set(WarehouseApiVersion::getFreshnessStaleSeconds, spec.staleSeconds())
                    .set(WarehouseApiVersion::getQuietSeconds, spec.quietSeconds())
                    .set(WarehouseApiVersion::getReconcileTolerance, spec.tolerance());
            setMapping(changes, spec.mapping());
        }
        applyUpdate(changes);
        return mapper.selectById(draft.getId());
    }

    @Transactional
    public WarehouseApiVersion publish(String projectId, String code, int version, int revision) {
        String user = requireProject(projectId, true);
        var draft = find(projectId, code, version);
        requireApproval(draft);
        var now = LocalDateTime.now().withNano(0);
        applyUpdate(update(draft, DRAFT, revision, user, now)
                .set(WarehouseApiVersion::getStatus, PUBLISHED)
                .set(WarehouseApiVersion::getPublishedBy, user).set(WarehouseApiVersion::getPublishedAt, now));
        return mapper.selectById(draft.getId());
    }

    /**
     * 发布审批门禁（默认关闭，保持存量调用不受影响）。
     *
     * <p>开启后，审批必须绑定到当前修订号：编辑会推进 revision，旧审批自然失效，
     * 必须重新审批。审批服务不可用时拒绝发布，不静默放行。</p>
     */
    private void requireApproval(WarehouseApiVersion draft) {
        if (!approvalRequired) {
            return;
        }
        if (approvals == null) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "审批服务未配置，无法校验发布审批");
        }
        try {
            if (approvals.findApproved(ApprovalApiClient.TYPE_WAREHOUSE_API,
                    draft.getId(), draft.getRevision()).isEmpty()) {
                throw new BusinessException(ResultCode.FORBIDDEN,
                        "该版本未取得审批或审批后已改稿，请重新发起审批");
            }
        } catch (IllegalStateException e) {
            throw new BusinessException(ResultCode.SYSTEM_ERROR, e.getMessage());
        }
    }

    /** 测试用：显式开启/关闭发布审批门禁。 */
    void configureApprovalGate(boolean required, ApprovalApiClient client) {
        this.approvalRequired = required;
        this.approvals = client;
    }

    @Transactional
    public WarehouseApiVersion offline(String projectId, String code, int version, int revision) {
        String user = requireProject(projectId, true);
        var release = find(projectId, code, version);
        var now = LocalDateTime.now().withNano(0);
        applyUpdate(update(release, PUBLISHED, revision, user, now)
                .set(WarehouseApiVersion::getStatus, OFFLINE)
                .set(WarehouseApiVersion::getOfflinedBy, user).set(WarehouseApiVersion::getOfflinedAt, now));
        return mapper.selectById(release.getId());
    }

    /** 返回体按版本声明的数据模式二选一：离线为 {@link OrderRegionGmv}，实时为 {@code RealtimeRegionGmv}。 */
    public Object invoke(String projectId, String code, int version, String bizDate) {
        requireProject(projectId, false);
        var release = requirePublished(projectId, code, version);
        return execute(release, bizDate);
    }

    /** 机器调用与人工试跑共用同一分派，保证两条入口口径一致。 */
    Object execute(WarehouseApiVersion release, String bizDate) {
        if (release.getDataMode() == REALTIME) {
            return realtime.regionGmv(contract(release), bizDate);
        }
        return materializations.orderRegionGmv(bizDate, release.getMaxRows());
    }

    /** 实时与批 ADS 逐地区对账；草稿态也允许跑，便于发布前先核对口径。 */
    public RealtimeReconcile reconcile(String projectId, String code, int version, String bizDate) {
        requireProject(projectId, true);
        var release = find(projectId, code, version);
        if (release.getStatus() == OFFLINE) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该 API 版本已下线，无法对账");
        }
        if (release.getDataMode() != REALTIME) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "仅实时契约支持与批 ADS 逐地区对账");
        }
        return realtime.reconcile(contract(release), bizDate);
    }

    RealtimeAdsQueryService.Contract contract(WarehouseApiVersion release) {
        return new RealtimeAdsQueryService.Contract(release.getRealtimeAssetId(), release.getRealtimeBatchAssetId(),
                release.getObservedTimezone(), release.getAmountUnit(),
                release.getFreshnessFreshSeconds(), release.getFreshnessStaleSeconds(), release.getMaxRows(),
                release.getQuietSeconds(), release.getReconcileTolerance(),
                RealtimeAdsQueryService.Mapping.of(
                        release.getRealtimeKeyColumn(), release.getRealtimeValueColumn(),
                        release.getRealtimeCountColumn(), release.getRealtimeObservedColumn(),
                        release.getRealtimeDateColumn(), release.getRealtimeValueDivisor(),
                        release.getRealtimeProgressTable(), release.getRealtimeProgressDateColumn(),
                        release.getRealtimeProgressAdvancedColumn(), release.getRealtimeProgressCountColumn(),
                        release.getRealtimeProgressEventTimeColumn()));
    }

    WarehouseApiVersion requirePublished(String projectId, String code, int version) {
        var release = find(projectId, code, version);
        if (release.getStatus() != PUBLISHED) {
            throw new BusinessException(ResultCode.NOT_FOUND, "该 API 版本未发布或已下线");
        }
        return release;
    }

    WarehouseApiVersion find(String projectId, String code, int version) {
        validateKey(code, version);
        var entity = mapper.selectOne(Wrappers.<WarehouseApiVersion>lambdaQuery()
                .eq(WarehouseApiVersion::getProjectId, projectId)
                .eq(WarehouseApiVersion::getApiCode, code)
                .eq(WarehouseApiVersion::getApiVersion, version));
        if (entity == null) throw new BusinessException(ResultCode.NOT_FOUND, "API 版本不存在");
        return entity;
    }

    /** 项目可见性与开发权限校验；控制器与同模块服务共用，故对外公开。 */
    public String requireProject(String projectId, boolean develop) {
        return requireProject(projectId, develop, false);
    }

    String requireProject(String projectId, boolean develop, boolean mustBeActive) {
        String user = MetadataHolder.getUserId();
        if (user == null || user.isBlank()) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (projectId == null || !PROJECT_ID.matcher(projectId).matches()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "项目 ID 无效");
        }
        var permission = permissions.getUserPermission(user);
        if (mustBeActive && (!user.equals(permission.getUserId()) || !Boolean.TRUE.equals(permission.getActive()))) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        var groups = permission.getProjectGroups();
        if (groups != null) {
            for (var group : groups) {
                if (!projectId.equals(group.getId())) continue;
                if (develop && (group.getCapabilityFlags() == null || !group.getCapabilityFlags().contains(DEVELOP))) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "当前角色缺少该项目的数据开发权限");
                }
                return user;
            }
        }
        throw new BusinessException(ResultCode.NOT_FOUND, "项目不存在或不可访问");
    }

    private static LambdaUpdateWrapper<WarehouseApiVersion> update(WarehouseApiVersion entity,
            WarehouseApiVersion.Status status, int revision, String user, LocalDateTime now) {
        if (revision < 1 || revision == Integer.MAX_VALUE) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "修订号无效");
        }
        return Wrappers.<WarehouseApiVersion>lambdaUpdate()
                .eq(WarehouseApiVersion::getId, entity.getId())
                .eq(WarehouseApiVersion::getStatus, status).eq(WarehouseApiVersion::getRevision, revision)
                .set(WarehouseApiVersion::getRevision, revision + 1)
                .set(WarehouseApiVersion::getUpdatedBy, user).set(WarehouseApiVersion::getUpdateTime, now);
    }

    private void applyUpdate(LambdaUpdateWrapper<WarehouseApiVersion> update) {
        if (mapper.update(null, update) != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "版本状态或修订号已变化，请刷新");
        }
    }

    private static void validateKey(String code, int version) {
        if (code == null || !API_CODE.matcher(code).matches() || version < 1) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "API 编码或版本号无效");
        }
    }

    private static String validateDraft(String name, int maxRows) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 100
                || maxRows < 1 || maxRows > MaterializationQueryService.ORDER_REGION_GMV_LIMIT) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "名称或最大返回行数无效");
        }
        return normalized;
    }

    /**
     * 归一化草稿：省略的字段按离线/默认值补齐，因此老调用方无需改动；
     * 只有声明 REALTIME 时才校验实时资产、时区、单位与时效阈值。
     */
    private static Spec spec(WarehouseApiDraft body) {
        if (body == null) throw new BusinessException(ResultCode.VALIDATE_FAILED, "缺少草稿内容");
        String name = validateDraft(body.name(), body.maxRows());
        var mode = body.dataMode() == null ? BATCH : body.dataMode();
        String timezone = blank(body.observedTimezone()) ? DEFAULT_TIMEZONE : body.observedTimezone().strip();
        String unit = blank(body.amountUnit()) ? DEFAULT_UNIT : body.amountUnit().strip().toUpperCase();
        int fresh = body.freshnessFreshSeconds() <= 0 ? DEFAULT_FRESH_SECONDS : body.freshnessFreshSeconds();
        int stale = body.freshnessStaleSeconds() <= 0 ? DEFAULT_STALE_SECONDS : body.freshnessStaleSeconds();
        int quiet = Math.max(0, body.quietSeconds());
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "契约时区无效");
        }
        if (mode != REALTIME) {
            // 离线契约不使用静默窗口、对账容差与实时列映射，统一归零，避免脏配置写入。
            return new Spec(name, body.maxRows(), BATCH, null, null, timezone, unit, fresh, stale, 0, null, null);
        }
        if (quiet > MAX_QUIET_SECONDS) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "静默窗口不能超过 3600 秒");
        }
        BigDecimal tolerance = body.reconcileTolerance();
        if (tolerance != null && (tolerance.signum() < 0 || tolerance.compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "对账容差须在 0 至 1 之间");
        }
        if (body.realtimeAssetId() == null || !ASSET_ID.matcher(body.realtimeAssetId()).matches()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "实时契约需要有效的 ADS 资产");
        }
        if (!blank(body.realtimeBatchAssetId()) && !ASSET_ID.matcher(body.realtimeBatchAssetId()).matches()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "对账基线资产无效");
        }
        if (!DEFAULT_UNIT.equals(unit)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "金额单位目前仅支持 CNY（固定两位小数）");
        }
        if (stale <= fresh || stale > MAX_THRESHOLD_SECONDS) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "时效阈值无效：STALE 须大于 FRESH 且不超过 24 小时");
        }
        return new Spec(name, body.maxRows(), REALTIME, body.realtimeAssetId(),
                blank(body.realtimeBatchAssetId()) ? null : body.realtimeBatchAssetId().strip(),
                timezone, unit, fresh, stale, quiet, tolerance, mapping(body.realtimeMapping()));
    }

    /**
     * 归一化实时列映射：空白回落 null（表示默认口径），非空必须是合法标识符。
     * 全部字段为空视为未配置，不往库里写空对象。
     */
    private static WarehouseApiDraft.RealtimeMapping mapping(WarehouseApiDraft.RealtimeMapping raw) {
        if (raw == null) {
            return null;
        }
        String key = column(raw.keyColumn(), "地区列");
        String value = column(raw.valueColumn(), "金额列");
        String count = column(raw.countColumn(), "订单数列");
        String observed = column(raw.observedColumn(), "观测时间列");
        String date = column(raw.dateColumn(), "业务日期列");
        String progressTable = column(raw.progressTable(), "推进信号表");
        String progressDate = column(raw.progressDateColumn(), "推进业务日期列");
        String advanced = column(raw.progressAdvancedColumn(), "推进时间列");
        String progressCount = column(raw.progressCountColumn(), "推进事件数列");
        String eventTime = column(raw.progressEventTimeColumn(), "最大事件时间列");
        Integer divisor = raw.valueDivisor();
        if (divisor != null && (divisor < 1 || divisor > MAX_VALUE_DIVISOR)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "金额换算除数须在 1 至 1000000 之间");
        }
        boolean empty = key == null && value == null && count == null && observed == null && date == null
                && divisor == null && progressTable == null && progressDate == null && advanced == null
                && progressCount == null && eventTime == null;
        if (empty) {
            return null;
        }
        return new WarehouseApiDraft.RealtimeMapping(key, value, count, observed, date, divisor,
                progressTable, progressDate, advanced, progressCount, eventTime);
    }

    private static String column(String value, String label) {
        if (blank(value)) {
            return null;
        }
        String normalized = value.strip();
        if (!COLUMN.matcher(normalized).matches()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, label + "不是合法的标识符");
        }
        return normalized;
    }

    private static void setMapping(WarehouseApiVersion draft, WarehouseApiDraft.RealtimeMapping mapping) {
        if (mapping == null) {
            return;
        }
        draft.setRealtimeKeyColumn(mapping.keyColumn());
        draft.setRealtimeValueColumn(mapping.valueColumn());
        draft.setRealtimeCountColumn(mapping.countColumn());
        draft.setRealtimeObservedColumn(mapping.observedColumn());
        draft.setRealtimeDateColumn(mapping.dateColumn());
        draft.setRealtimeValueDivisor(mapping.valueDivisor());
        draft.setRealtimeProgressTable(mapping.progressTable());
        draft.setRealtimeProgressDateColumn(mapping.progressDateColumn());
        draft.setRealtimeProgressAdvancedColumn(mapping.progressAdvancedColumn());
        draft.setRealtimeProgressCountColumn(mapping.progressCountColumn());
        draft.setRealtimeProgressEventTimeColumn(mapping.progressEventTimeColumn());
    }

    /** 显式声明数据模式时整体替换契约：省略的映射要写回 NULL，而不是保留旧值。 */
    private static void setMapping(LambdaUpdateWrapper<WarehouseApiVersion> update,
                                   WarehouseApiDraft.RealtimeMapping mapping) {
        update.set(WarehouseApiVersion::getRealtimeKeyColumn, mapping == null ? null : mapping.keyColumn())
                .set(WarehouseApiVersion::getRealtimeValueColumn, mapping == null ? null : mapping.valueColumn())
                .set(WarehouseApiVersion::getRealtimeCountColumn, mapping == null ? null : mapping.countColumn())
                .set(WarehouseApiVersion::getRealtimeObservedColumn, mapping == null ? null : mapping.observedColumn())
                .set(WarehouseApiVersion::getRealtimeDateColumn, mapping == null ? null : mapping.dateColumn())
                .set(WarehouseApiVersion::getRealtimeValueDivisor, mapping == null ? null : mapping.valueDivisor())
                .set(WarehouseApiVersion::getRealtimeProgressTable, mapping == null ? null : mapping.progressTable())
                .set(WarehouseApiVersion::getRealtimeProgressDateColumn,
                        mapping == null ? null : mapping.progressDateColumn())
                .set(WarehouseApiVersion::getRealtimeProgressAdvancedColumn,
                        mapping == null ? null : mapping.progressAdvancedColumn())
                .set(WarehouseApiVersion::getRealtimeProgressCountColumn,
                        mapping == null ? null : mapping.progressCountColumn())
                .set(WarehouseApiVersion::getRealtimeProgressEventTimeColumn,
                        mapping == null ? null : mapping.progressEventTimeColumn());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Spec(String name, int maxRows, WarehouseApiVersion.DataMode mode, String realtimeAssetId,
                        String batchAssetId, String timezone, String amountUnit, int freshSeconds, int staleSeconds,
                        int quietSeconds, BigDecimal tolerance,
                        WarehouseApiDraft.RealtimeMapping mapping) {}
}
