package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private static final String DEVELOP = "task:develop";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";
    private static final String DEFAULT_UNIT = "CNY";
    private static final int DEFAULT_FRESH_SECONDS = 60;
    private static final int DEFAULT_STALE_SECONDS = 300;
    private static final int MAX_THRESHOLD_SECONDS = 86_400;

    private final WarehouseApiVersionMapper mapper;
    private final PermissionApiClient permissions;
    private final MaterializationQueryService materializations;
    private final RealtimeAdsQueryService realtime;

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
        Spec spec = spec(body);
        applyUpdate(update(draft, DRAFT, revision, user, LocalDateTime.now().withNano(0))
                .set(WarehouseApiVersion::getApiName, spec.name())
                .set(WarehouseApiVersion::getMaxRows, spec.maxRows())
                .set(WarehouseApiVersion::getDataMode, spec.mode())
                .set(WarehouseApiVersion::getRealtimeAssetId, spec.realtimeAssetId())
                .set(WarehouseApiVersion::getRealtimeBatchAssetId, spec.batchAssetId())
                .set(WarehouseApiVersion::getObservedTimezone, spec.timezone())
                .set(WarehouseApiVersion::getAmountUnit, spec.amountUnit())
                .set(WarehouseApiVersion::getFreshnessFreshSeconds, spec.freshSeconds())
                .set(WarehouseApiVersion::getFreshnessStaleSeconds, spec.staleSeconds()));
        return mapper.selectById(draft.getId());
    }

    @Transactional
    public WarehouseApiVersion publish(String projectId, String code, int version, int revision) {
        String user = requireProject(projectId, true);
        var draft = find(projectId, code, version);
        var now = LocalDateTime.now().withNano(0);
        applyUpdate(update(draft, DRAFT, revision, user, now)
                .set(WarehouseApiVersion::getStatus, PUBLISHED)
                .set(WarehouseApiVersion::getPublishedBy, user).set(WarehouseApiVersion::getPublishedAt, now));
        return mapper.selectById(draft.getId());
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
                release.getFreshnessFreshSeconds(), release.getFreshnessStaleSeconds(), release.getMaxRows());
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
        try {
            ZoneId.of(timezone);
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "契约时区无效");
        }
        if (mode != REALTIME) {
            return new Spec(name, body.maxRows(), BATCH, null, null, timezone, unit, fresh, stale);
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
                timezone, unit, fresh, stale);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Spec(String name, int maxRows, WarehouseApiVersion.DataMode mode, String realtimeAssetId,
                        String batchAssetId, String timezone, String amountUnit, int freshSeconds, int staleSeconds) {}
}
