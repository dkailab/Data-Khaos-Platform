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
import com.datakhaos.query.entity.WarehouseApiVersion;
import com.datakhaos.query.mapper.WarehouseApiVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

import static com.datakhaos.query.entity.WarehouseApiVersion.Status.DRAFT;
import static com.datakhaos.query.entity.WarehouseApiVersion.Status.OFFLINE;
import static com.datakhaos.query.entity.WarehouseApiVersion.Status.PUBLISHED;

@Service
@RequiredArgsConstructor
public class WarehouseApiService {
    private static final Pattern PROJECT_ID = Pattern.compile("[a-zA-Z0-9_-]{1,32}");
    private static final Pattern API_CODE = Pattern.compile("[a-z][a-z0-9-]{0,47}");
    private static final String DEVELOP = "task:develop";

    private final WarehouseApiVersionMapper mapper;
    private final PermissionApiClient permissions;
    private final MaterializationQueryService materializations;

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

    public WarehouseApiVersion create(String projectId, String code, int version, String name, int maxRows) {
        String user = requireProject(projectId, true);
        validateKey(code, version);
        String normalizedName = validateDraft(name, maxRows);
        var draft = new WarehouseApiVersion();
        draft.setProjectId(projectId);
        draft.setApiCode(code);
        draft.setApiVersion(version);
        draft.setApiName(normalizedName);
        draft.setMaxRows(maxRows);
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
    public WarehouseApiVersion edit(String projectId, String code, int version, int revision, String name, int maxRows) {
        String user = requireProject(projectId, true);
        var draft = find(projectId, code, version);
        String normalizedName = validateDraft(name, maxRows);
        applyUpdate(update(draft, DRAFT, revision, user, LocalDateTime.now().withNano(0))
                .set(WarehouseApiVersion::getApiName, normalizedName).set(WarehouseApiVersion::getMaxRows, maxRows));
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

    public OrderRegionGmv invoke(String projectId, String code, int version, String bizDate) {
        requireProject(projectId, false);
        var release = requirePublished(projectId, code, version);
        return materializations.orderRegionGmv(bizDate, release.getMaxRows());
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

    String requireProject(String projectId, boolean develop) {
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
}
