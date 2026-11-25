package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.query.entity.WarehouseApiApp;
import com.datakhaos.query.entity.WarehouseApiGrant;
import com.datakhaos.query.entity.WarehouseApiVersion;
import com.datakhaos.query.mapper.WarehouseApiAppMapper;
import com.datakhaos.query.mapper.WarehouseApiGrantMapper;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 数仓 API 应用注册与授权。
 *
 * <p>应用是项目内的稳定身份（appId），授权以 {@code appId + apiVersionId} 为唯一键并携带配额与过期时间；
 * 运行主体是 permission 侧的独立账号，数据范围由该账号自身的表/行/列权限决定，这里只负责校验其有效性。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseApiAppService {
    private static final int MAX_QUOTA = 100_000;
    private static final int NAME_LIMIT = 100;

    private final WarehouseApiAppMapper apps;
    private final WarehouseApiGrantMapper grants;
    private final WarehouseApiVersionMapper versions;
    private final WarehouseApiService apis;
    private final PermissionApiClient permissions;

    // ==================== 应用 ====================

    public PageResult<WarehouseApiApp> list(String projectId, long current, long size) {
        apis.requireProject(projectId, true);
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        var page = apps.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiApp>lambdaQuery()
                .eq(WarehouseApiApp::getProjectId, projectId)
                .orderByDesc(WarehouseApiApp::getCreateTime, WarehouseApiApp::getId));
        return PageResult.of(current, size, page.getTotal(), page.getRecords());
    }

    public WarehouseApiApp detail(String projectId, String appId) {
        apis.requireProject(projectId, true);
        return find(projectId, appId);
    }

    public WarehouseApiApp create(String projectId, String name, String owner, String runtimeSubject) {
        String user = apis.requireProject(projectId, true);
        String normalizedName = requireName(name);
        String subject = requireRuntimeSubject(projectId, runtimeSubject);
        var app = new WarehouseApiApp();
        var now = now();
        app.setProjectId(projectId);
        app.setAppName(normalizedName);
        app.setOwner(owner == null || owner.isBlank() ? user : owner.strip());
        app.setRuntimeSubject(subject);
        app.setStatus(WarehouseApiApp.Status.ENABLED);
        app.setRevision(1);
        app.setCreatedBy(user);
        app.setCreateTime(now);
        app.setUpdatedBy(user);
        app.setUpdateTime(now);
        try {
            apps.insert(app);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "此项目已存在同名应用");
        }
        return app;
    }

    public WarehouseApiApp update(String projectId, String appId, int revision,
            String name, String owner, String runtimeSubject) {
        String user = apis.requireProject(projectId, true);
        var app = find(projectId, appId);
        var update = revisionUpdate(app, revision, user, now());
        if (name != null) update.set(WarehouseApiApp::getAppName, requireName(name));
        if (owner != null && !owner.isBlank()) update.set(WarehouseApiApp::getOwner, owner.strip());
        if (runtimeSubject != null && !runtimeSubject.isBlank()) {
            update.set(WarehouseApiApp::getRuntimeSubject, requireRuntimeSubject(projectId, runtimeSubject));
        }
        try {
            apply(update);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "此项目已存在同名应用");
        }
        return apps.selectById(app.getId());
    }

    public WarehouseApiApp setEnabled(String projectId, String appId, int revision, boolean enabled) {
        String user = apis.requireProject(projectId, true);
        var app = find(projectId, appId);
        apply(revisionUpdate(app, revision, user, now())
                .set(WarehouseApiApp::getStatus, enabled ? WarehouseApiApp.Status.ENABLED : WarehouseApiApp.Status.DISABLED));
        return apps.selectById(app.getId());
    }

    /** 调用链使用：应用必须存在且处于启用状态，否则在查询前即被拒绝。 */
    public WarehouseApiApp requireEnabled(String projectId, String appId) {
        var app = apps.selectById(appId);
        if (app == null || !projectId.equals(app.getProjectId())) {
            throw new BusinessException(ResultCode.UNAUTHORIZED, "应用不存在或不属于该项目");
        }
        if (app.getStatus() != WarehouseApiApp.Status.ENABLED) {
            throw new BusinessException(ResultCode.FORBIDDEN, "应用已停用");
        }
        return app;
    }

    // ==================== 授权 ====================

    public GrantView grant(String projectId, String apiCode, int version, String appId,
            int quotaPerMinute, Instant expiresAt) {
        String user = apis.requireProject(projectId, true);
        var app = find(projectId, appId);
        var release = apis.requirePublished(projectId, apiCode, version);
        if (quotaPerMinute < 1 || quotaPerMinute > MAX_QUOTA) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "配额需在 1 至 " + MAX_QUOTA + " 次/分钟之间");
        }
        LocalDateTime expiry = expiresAt == null ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC).withNano(0);
        if (expiry != null && !expiry.isAfter(now())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "授权过期时间必须晚于当前时间");
        }
        var existing = findGrant(app.getId(), release.getId());
        if (existing != null) {
            if (existing.getRevokedAt() == null) {
                throw new BusinessException(ResultCode.DUPLICATE_KEY, "该应用已授权此 API 版本，请编辑配额或先回收");
            }
            // 回收后重新授权：沿用同一行，清空回收状态。
            var now = now();
            applyGrant(Wrappers.<WarehouseApiGrant>lambdaUpdate()
                    .eq(WarehouseApiGrant::getId, existing.getId())
                    .isNotNull(WarehouseApiGrant::getRevokedAt)
                    .set(WarehouseApiGrant::getQuotaPerMinute, quotaPerMinute)
                    .set(WarehouseApiGrant::getExpiresAt, expiry)
                    .set(WarehouseApiGrant::getRevokedBy, null).set(WarehouseApiGrant::getRevokedAt, null)
                    .set(WarehouseApiGrant::getUpdatedBy, user).set(WarehouseApiGrant::getUpdateTime, now));
            return view(grants.selectById(existing.getId()), release);
        }
        var row = new WarehouseApiGrant();
        var now = now();
        row.setAppId(app.getId());
        row.setApiVersionId(release.getId());
        row.setQuotaPerMinute(quotaPerMinute);
        row.setExpiresAt(expiry);
        row.setCreatedBy(user);
        row.setCreateTime(now);
        row.setUpdatedBy(user);
        row.setUpdateTime(now);
        try {
            grants.insert(row);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "该应用已授权此 API 版本");
        }
        return view(row, release);
    }

    public PageResult<GrantView> listGrants(String projectId, String appId, long current, long size) {
        apis.requireProject(projectId, true);
        var app = find(projectId, appId);
        if (current < 1 || current > 10_000 || size < 1 || size > 100) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数超出范围");
        }
        var page = grants.selectPage(new Page<>(current, size), Wrappers.<WarehouseApiGrant>lambdaQuery()
                .eq(WarehouseApiGrant::getAppId, app.getId())
                .orderByDesc(WarehouseApiGrant::getCreateTime, WarehouseApiGrant::getId));
        Map<String, WarehouseApiVersion> releases = page.getRecords().isEmpty()
                ? Map.of()
                : versions.selectBatchIds(page.getRecords().stream()
                        .map(WarehouseApiGrant::getApiVersionId).distinct().toList())
                .stream().collect(Collectors.toMap(WarehouseApiVersion::getId, Function.identity()));
        return PageResult.of(current, size, page.getTotal(),
                page.getRecords().stream().map(row -> view(row, releases.get(row.getApiVersionId()))).toList());
    }

    public void revokeGrant(String projectId, String appId, String grantId) {
        String user = apis.requireProject(projectId, true);
        var app = find(projectId, appId);
        var row = grants.selectById(grantId);
        if (row == null || !app.getId().equals(row.getAppId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "授权不存在");
        }
        applyGrant(Wrappers.<WarehouseApiGrant>lambdaUpdate()
                .eq(WarehouseApiGrant::getId, grantId).eq(WarehouseApiGrant::getAppId, app.getId())
                .isNull(WarehouseApiGrant::getRevokedAt)
                .set(WarehouseApiGrant::getRevokedBy, user).set(WarehouseApiGrant::getRevokedAt, now()));
    }

    /** 调用链使用：确认 (appId, apiVersionId) 授权有效，否则在查询前拒绝。 */
    public WarehouseApiGrant requireActiveGrant(String appId, String apiVersionId) {
        var grant = findGrant(appId, apiVersionId);
        if (grant == null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "应用未获该 API 版本的授权");
        }
        if (grant.getRevokedAt() != null) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该 API 版本的授权已回收");
        }
        if (grant.getExpiresAt() != null && !grant.getExpiresAt().isAfter(now())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该 API 版本的授权已过期");
        }
        return grant;
    }

    /** 校验运行主体：必须是可用且属于该项目的 permission 账号，且不能是内置管理员。 */
    public String requireRuntimeSubject(String projectId, String runtimeSubject) {
        String subject = runtimeSubject == null ? "" : runtimeSubject.strip();
        if (subject.isEmpty()) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运行主体不能为空");
        }
        if (CommonConstants.DEFAULT_ADMIN_ID.equals(subject)) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运行主体不能使用内置管理员");
        }
        var permission = permissions.getUserPermission(subject);
        if (permission == null || !subject.equals(permission.getUserId())
                || !Boolean.TRUE.equals(permission.getActive())) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运行主体不存在或已停用");
        }
        var groups = permission.getProjectGroups();
        if (groups == null || groups.stream().noneMatch(group -> projectId.equals(group.getId()))) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "运行主体不属于该项目");
        }
        return subject;
    }

    /** 签发凭据前复用同一套校验，避免对未授权版本发出口令。 */
    public WarehouseApiApp requireGrantedApp(String projectId, String appId, String apiVersionId) {
        var app = requireEnabled(projectId, appId);
        requireActiveGrant(appId, apiVersionId);
        return app;
    }

    WarehouseApiApp find(String projectId, String appId) {
        var app = appId == null ? null : apps.selectById(appId);
        if (app == null || !projectId.equals(app.getProjectId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "应用不存在");
        }
        return app;
    }

    private WarehouseApiGrant findGrant(String appId, String apiVersionId) {
        return grants.selectOne(Wrappers.<WarehouseApiGrant>lambdaQuery()
                .eq(WarehouseApiGrant::getAppId, appId)
                .eq(WarehouseApiGrant::getApiVersionId, apiVersionId));
    }

    private static GrantView view(WarehouseApiGrant row, WarehouseApiVersion release) {
        return new GrantView(row.getId(), row.getAppId(),
                release == null ? null : release.getApiCode(),
                release == null ? null : release.getApiVersion(),
                row.getApiVersionId(), row.getQuotaPerMinute(),
                utc(row.getExpiresAt()), row.getRevokedBy(), utc(row.getRevokedAt()), utc(row.getCreateTime()));
    }

    private static LambdaUpdateWrapper<WarehouseApiApp> revisionUpdate(WarehouseApiApp app, int revision,
            String user, LocalDateTime now) {
        if (revision < 1 || revision == Integer.MAX_VALUE) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "修订号无效");
        }
        return Wrappers.<WarehouseApiApp>lambdaUpdate()
                .eq(WarehouseApiApp::getId, app.getId())
                .eq(WarehouseApiApp::getRevision, revision)
                .set(WarehouseApiApp::getRevision, revision + 1)
                .set(WarehouseApiApp::getUpdatedBy, user).set(WarehouseApiApp::getUpdateTime, now);
    }

    private void apply(LambdaUpdateWrapper<WarehouseApiApp> update) {
        if (apps.update(null, update) != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "应用状态或修订号已变化，请刷新");
        }
    }

    private void applyGrant(LambdaUpdateWrapper<WarehouseApiGrant> update) {
        if (grants.update(null, update) != 1) {
            throw new BusinessException(ResultCode.BUSINESS_ERROR, "授权状态已变化，请刷新");
        }
    }

    private static String requireName(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > NAME_LIMIT) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "应用名称需为 1 至 " + NAME_LIMIT + " 个字符");
        }
        return normalized;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).withNano(0);
    }

    private static Instant utc(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    public record GrantView(String id, String appId, String apiCode, Integer apiVersion, String apiVersionId,
                            int quotaPerMinute, Instant expiresAt, String revokedBy, Instant revokedAt,
                            Instant createTime) {}
}
