package com.datakhaos.mart.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.ColumnPolicy;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RewriteResult;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RowPolicy;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.mart.api.model.DimensionDto;
import com.datakhaos.mart.api.model.MarketModelDto;
import com.datakhaos.mart.api.model.MetricDto;
import com.datakhaos.mart.api.model.ModelDto;
import com.datakhaos.mart.entity.MartDimLevel;
import com.datakhaos.mart.entity.MartDimension;
import com.datakhaos.mart.entity.MartMetric;
import com.datakhaos.mart.entity.MartModel;
import com.datakhaos.mart.entity.MartModelRel;
import com.datakhaos.mart.entity.MartWarehouseLayer;
import com.datakhaos.mart.mapper.MartDimLevelMapper;
import com.datakhaos.mart.mapper.MartDimensionMapper;
import com.datakhaos.mart.mapper.MartMetricMapper;
import com.datakhaos.mart.mapper.MartModelMapper;
import com.datakhaos.mart.mapper.MartModelRelMapper;
import com.datakhaos.mart.mapper.MartWarehouseLayerMapper;
import com.datakhaos.common.model.R;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.permission.api.service.PermissionConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据集市服务：模型建模、指标/维度管理、关联关系与数据预览。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MartService {

    private final MartModelMapper modelMapper;
    private final MartMetricMapper metricMapper;
    private final MartDimensionMapper dimensionMapper;
    private final MartDimLevelMapper dimLevelMapper;
    private final MartModelRelMapper modelRelMapper;
    private final MartWarehouseLayerMapper layerMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final PermissionApiClient permissionApiClient;

    // ==================== 权限上下文 ====================

    /** 当前请求的权限上下文 */
    private record AuthContext(String userId, String projectGroupId, Set<String> capabilities, boolean superAdmin) {
        boolean hasCap(String cap) {
            return superAdmin || (capabilities != null && capabilities.contains(cap));
        }
    }

    private AuthContext currentAuth() {
        String userId = MetadataHolder.getUserId();
        boolean sa = MetadataHolder.isSuperAdmin();
        if (userId == null) {
            return new AuthContext(null, null, Set.of(), sa);
        }
        if (sa) {
            return new AuthContext(userId, null, null, true);
        }
        UserPermissionDto perm = permissionApiClient.getUserPermission(userId);
        String pg = perm.getProjectGroupId();
        List<String> caps = perm.getCapabilityFlags() == null ? List.of() : perm.getCapabilityFlags();
        return new AuthContext(userId, pg, new HashSet<>(caps), false);
    }

    private void requireCap(AuthContext ctx, String cap) {
        if (!ctx.hasCap(cap)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无「" + cap + "」操作权限");
        }
    }

    /** 校验某模型属于当前项目组（或超管），否则拒绝浏览 */
    private void checkModelGroup(AuthContext ctx, MartModel model) {
        if (ctx.superAdmin()) {
            return;
        }
        if (model == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模型不存在");
        }
        if (StrUtil.isBlank(ctx.projectGroupId()) || !ctx.projectGroupId().equals(model.getProjectGroupId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该项目组的模型");
        }
    }

    // ==================== 数仓分层 ====================

    public List<MartWarehouseLayer> listLayers() {
        return layerMapper.selectList(new LambdaQueryWrapper<MartWarehouseLayer>()
                .eq(MartWarehouseLayer::getStatus, 1)
                .orderByAsc(MartWarehouseLayer::getSortOrder));
    }

    // ==================== 模型 ====================

    public PageResult<MartModel> modelPage(long current, long size, String keyword, Integer status, String layerId) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        Page<MartModel> page = modelMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MartModel>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()), MartModel::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(layerId), MartModel::getLayerId, layerId)
                        .like(StrUtil.isNotBlank(keyword), MartModel::getModelName, keyword)
                        .eq(status != null, MartModel::getStatus, status)
                        .orderByDesc(MartModel::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public MartModel getModel(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        MartModel model = modelMapper.selectById(id);
        if (model == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模型不存在: " + id);
        }
        checkModelGroup(ctx, model);
        return model;
    }

    /**
     * 模型市场分页：仅返回已发布(status=1)模型，按当前项目组隔离，
     * 附带指标/维度/关联统计与分层信息。要求 model:browse 能力位。
     */
    public PageResult<MarketModelDto> marketPage(long current, long size, String keyword, String layerId) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        Page<MartModel> page = modelMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MartModel>()
                        .eq(MartModel::getStatus, 1)
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()), MartModel::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(layerId), MartModel::getLayerId, layerId)
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(MartModel::getModelName, keyword)
                                .or().like(MartModel::getModelCode, keyword))
                        .orderByDesc(MartModel::getUpdateTime));
        List<MarketModelDto> dtos = page.getRecords().stream().map(this::toMarketDto).toList();
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), dtos);
    }

    /** 组装市场卡片 DTO（含指标/维度/关联统计与分层信息） */
    private MarketModelDto toMarketDto(MartModel model) {
        MarketModelDto dto = new MarketModelDto();
        dto.setId(model.getId());
        dto.setModelName(model.getModelName());
        dto.setModelCode(model.getModelCode());
        dto.setModelType(model.getModelType());
        dto.setDatasourceId(model.getDatasourceId());
        dto.setDescription(model.getDescription());
        dto.setLayerId(model.getLayerId());
        dto.setProjectGroupId(model.getProjectGroupId());
        dto.setVersion(model.getVersion());
        dto.setUpdateTime(model.getUpdateTime());
        // 统计
        dto.setMetricCount(metricMapper.selectCount(new LambdaQueryWrapper<MartMetric>()
                .eq(MartMetric::getModelId, model.getId())));
        dto.setDimensionCount(dimensionMapper.selectCount(new LambdaQueryWrapper<MartDimension>()
                .eq(MartDimension::getModelId, model.getId())));
        dto.setRelCount(modelRelMapper.selectCount(new LambdaQueryWrapper<MartModelRel>()
                .eq(MartModelRel::getModelId, model.getId())));
        // 分层信息
        if (StrUtil.isNotBlank(model.getLayerId())) {
            MartWarehouseLayer layer = layerMapper.selectById(model.getLayerId());
            if (layer != null) {
                dto.setLayerCode(layer.getLayerCode());
                dto.setLayerName(layer.getLayerName());
            }
        }
        return dto;
    }

    @Transactional(rollbackFor = Exception.class)
    public void createModel(MartModel model) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        validateModel(model);
        if (modelMapper.selectCount(new LambdaQueryWrapper<MartModel>()
                .eq(MartModel::getModelCode, model.getModelCode())) > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "模型编码已存在: " + model.getModelCode());
        }
        model.setStatus(model.getStatus() == null ? 0 : model.getStatus());
        model.setVersion(model.getVersion() == null ? 1 : model.getVersion());
        model.setModelType(model.getModelType() == null ? "STAR" : model.getModelType());
        if (!ctx.superAdmin()) {
            model.setProjectGroupId(ctx.projectGroupId());
        }
        modelMapper.insert(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateModel(MartModel model) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        if (StrUtil.isBlank(model.getId())) {
            throw new BusinessException("模型ID不能为空");
        }
        MartModel exist = getModel(model.getId());
        checkModelGroup(ctx, exist);
        if (!ctx.superAdmin()) {
            model.setProjectGroupId(exist.getProjectGroupId());
        }
        modelMapper.updateById(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteModel(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        MartModel exist = getModel(id);
        checkModelGroup(ctx, exist);
        metricMapper.delete(new LambdaQueryWrapper<MartMetric>().eq(MartMetric::getModelId, id));
        dimensionMapper.delete(new LambdaQueryWrapper<MartDimension>().eq(MartDimension::getModelId, id));
        modelRelMapper.delete(new LambdaQueryWrapper<MartModelRel>().eq(MartModelRel::getModelId, id));
        modelMapper.deleteById(id);
    }

    /** 发布：草稿 -> 已发布，版本号 +1 */
    @Transactional(rollbackFor = Exception.class)
    public void publish(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_PUBLISH);
        MartModel model = getModel(id);
        checkModelGroup(ctx, model);
        model.setStatus(1);
        model.setVersion((model.getVersion() == null ? 1 : model.getVersion()) + 1);
        modelMapper.updateById(model);
    }

    /** 下线 */
    @Transactional(rollbackFor = Exception.class)
    public void offline(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_PUBLISH);
        MartModel model = getModel(id);
        checkModelGroup(ctx, model);
        model.setStatus(2);
        modelMapper.updateById(model);
    }

    // ==================== 指标 ====================

    public PageResult<MartMetric> metricPage(long current, long size, String modelId, String keyword) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        Page<MartMetric> page = metricMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MartMetric>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()), MartMetric::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(modelId), MartMetric::getModelId, modelId)
                        .like(StrUtil.isNotBlank(keyword), MartMetric::getMetricName, keyword)
                        .orderByDesc(MartMetric::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public List<MetricDto> metricDtos(String modelId) {
        return metricMapper.selectList(new LambdaQueryWrapper<MartMetric>()
                        .eq(MartMetric::getModelId, modelId)
                        .eq(MartMetric::getStatus, 1))
                .stream().map(this::toMetricDto).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void createMetric(MartMetric metric) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        if (StrUtil.isBlank(metric.getMetricName()) || StrUtil.isBlank(metric.getMetricCode())) {
            throw new BusinessException("指标名称与编码不能为空");
        }
        if (metricMapper.selectCount(new LambdaQueryWrapper<MartMetric>()
                .eq(MartMetric::getMetricCode, metric.getMetricCode())) > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "指标编码已存在: " + metric.getMetricCode());
        }
        metric.setMetricType(metric.getMetricType() == null ? "ATOMIC" : metric.getMetricType());
        metric.setDataType(metric.getDataType() == null ? "BIGINT" : metric.getDataType());
        metric.setStatus(metric.getStatus() == null ? 1 : metric.getStatus());
        if (!ctx.superAdmin()) {
            metric.setProjectGroupId(ctx.projectGroupId());
        }
        metricMapper.insert(metric);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateMetric(MartMetric metric) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        if (StrUtil.isBlank(metric.getId())) {
            throw new BusinessException("指标ID不能为空");
        }
        metricMapper.updateById(metric);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteMetric(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        metricMapper.deleteById(id);
    }

    // ==================== 维度 ====================

    public PageResult<MartDimension> dimensionPage(long current, long size, String modelId) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        Page<MartDimension> page = dimensionMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MartDimension>()
                        .eq(!ctx.superAdmin() && StrUtil.isNotBlank(ctx.projectGroupId()), MartDimension::getProjectGroupId, ctx.projectGroupId())
                        .eq(StrUtil.isNotBlank(modelId), MartDimension::getModelId, modelId)
                        .orderByAsc(MartDimension::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public List<DimensionDto> dimensionDtos(String modelId) {
        return dimensionMapper.selectList(new LambdaQueryWrapper<MartDimension>()
                        .eq(MartDimension::getModelId, modelId)
                        .eq(MartDimension::getStatus, 1))
                .stream().map(this::toDimensionDto).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void createDimension(MartDimension dimension) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        if (StrUtil.isBlank(dimension.getDimName()) || StrUtil.isBlank(dimension.getDimCode())) {
            throw new BusinessException("维度名称与编码不能为空");
        }
        if (dimensionMapper.selectCount(new LambdaQueryWrapper<MartDimension>()
                .eq(MartDimension::getDimCode, dimension.getDimCode())) > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "维度编码已存在: " + dimension.getDimCode());
        }
        dimension.setDimType(dimension.getDimType() == null ? "COMMON" : dimension.getDimType());
        dimension.setStatus(dimension.getStatus() == null ? 1 : dimension.getStatus());
        if (!ctx.superAdmin()) {
            dimension.setProjectGroupId(ctx.projectGroupId());
        }
        dimensionMapper.insert(dimension);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDimension(MartDimension dimension) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        if (StrUtil.isBlank(dimension.getId())) {
            throw new BusinessException("维度ID不能为空");
        }
        dimensionMapper.updateById(dimension);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDimension(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        dimLevelMapper.delete(new LambdaQueryWrapper<MartDimLevel>().eq(MartDimLevel::getDimId, id));
        dimensionMapper.deleteById(id);
    }

    /** 维度层级 */
    public List<MartDimLevel> levels(String dimId) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        return dimLevelMapper.selectList(new LambdaQueryWrapper<MartDimLevel>()
                .eq(MartDimLevel::getDimId, dimId)
                .orderByAsc(MartDimLevel::getLevelOrder));
    }

    /** 保存维度层级（全量替换） */
    @Transactional(rollbackFor = Exception.class)
    public void saveLevels(String dimId, List<MartDimLevel> levels) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        dimLevelMapper.delete(new LambdaQueryWrapper<MartDimLevel>().eq(MartDimLevel::getDimId, dimId));
        if (levels != null) {
            int order = 1;
            for (MartDimLevel level : levels) {
                level.setId(null);
                level.setDimId(dimId);
                level.setLevelOrder(level.getLevelOrder() == null ? order++ : level.getLevelOrder());
                dimLevelMapper.insert(level);
            }
        }
    }

    // ==================== 关联关系 ====================

    public List<MartModelRel> rels(String modelId) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_BROWSE);
        return modelRelMapper.selectList(new LambdaQueryWrapper<MartModelRel>()
                .eq(MartModelRel::getModelId, modelId)
                .orderByAsc(MartModelRel::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveRel(String modelId, MartModelRel rel) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        getModel(modelId);
        rel.setModelId(modelId);
        rel.setJoinType(rel.getJoinType() == null ? "INNER" : rel.getJoinType());
        if (!ctx.superAdmin()) {
            rel.setProjectGroupId(ctx.projectGroupId());
        }
        if (StrUtil.isBlank(rel.getId())) {
            modelRelMapper.insert(rel);
        } else {
            modelRelMapper.updateById(rel);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteRel(String id) {
        AuthContext ctx = currentAuth();
        requireCap(ctx, PermissionConstants.CAP_MODEL_DEVELOP);
        modelRelMapper.deleteById(id);
    }

    // ==================== 模型详情 & 预览 ====================

    /** 模型详情（含指标/维度/关联） */
    public Map<String, Object> modelDetail(String id) {
        MartModel model = getModel(id);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model", toModelDto(model));
        detail.put("metrics", metricDtos(id));
        detail.put("dimensions", dimensionDtos(id));
        detail.put("rels", rels(id));
        return detail;
    }

    /** 预览模型数据：SELECT 事实表前 100 行，应用行/列权限改写 */
    public QueryResult preview(String id) {
        MartModel model = getModel(id);
        if (StrUtil.isBlank(model.getDatasourceId())) {
            throw new BusinessException("模型未绑定数据源，无法预览");
        }
        String factTable = modelRelMapper.selectList(new LambdaQueryWrapper<MartModelRel>()
                        .eq(MartModelRel::getModelId, id))
                .stream().map(MartModelRel::getFactTable).findFirst().orElse(null);
        if (StrUtil.isBlank(factTable)) {
            throw new BusinessException("模型未配置事实表，无法预览");
        }
        String sql = "SELECT * FROM " + factTable + " LIMIT 100";
        // 行/列权限改写
        sql = applyPermissionRewrite(sql);
        R<QueryResult> r = datasourceApiClient.executeRaw(model.getDatasourceId(), sql);
        if (r == null || r.getCode() != 0) {
            throw new BusinessException(r == null ? "预览失败" : r.getMsg());
        }
        return r.getData();
    }

    /**
     * 查询指标样例数据（指标中心内嵌预览）。构建聚合 SQL 后执行，应用行/列权限改写。
     */
    public QueryResult previewMetric(String metricId) {
        MartMetric metric = metricMapper.selectById(metricId);
        if (metric == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "指标不存在: " + metricId);
        }
        getModel(metric.getModelId());
        String sql = "SELECT " + metric.getMetricCode() + " FROM " + resolveFactTable(metric.getModelId()) + " LIMIT 100";
        sql = applyPermissionRewrite(sql);
        MartModel model = modelMapper.selectById(metric.getModelId());
        R<QueryResult> r = datasourceApiClient.executeRaw(model.getDatasourceId(), sql);
        if (r == null || r.getCode() != 0) {
            throw new BusinessException(r == null ? "指标预览失败" : r.getMsg());
        }
        return r.getData();
    }

    private String resolveFactTable(String modelId) {
        return modelRelMapper.selectList(new LambdaQueryWrapper<MartModelRel>()
                        .eq(MartModelRel::getModelId, modelId))
                .stream().map(MartModelRel::getFactTable).findFirst().orElseThrow(() -> new BusinessException("模型未配置事实表"));
    }

    /** 行/列权限 SQL 改写：针对当前用户上下文 */
    private String applyPermissionRewrite(String sql) {
        String userId = MetadataHolder.getUserId();
        if (StrUtil.isBlank(sql) || StrUtil.isBlank(userId) || MetadataHolder.isSuperAdmin()) {
            return sql;
        }
        try {
            Set<String> tables = extractSimpleTables(sql);
            if (tables.isEmpty()) return sql;

            var userPerm = permissionApiClient.getUserPermission(userId);
            List<RowPolicy> rowPolicies = new ArrayList<>();
            List<ColumnPolicy> columnPolicies = new ArrayList<>();
            for (String table : tables) {
                rowPolicies.addAll(permissionApiClient.getRowPolicies(userId, userPerm, table));
                columnPolicies.addAll(permissionApiClient.getColumnPolicies(userId, userPerm, table));
            }
            if (rowPolicies.isEmpty() && columnPolicies.isEmpty()) return sql;

            RewriteResult result = SqlRewriteEngine.rewrite(sql, rowPolicies, columnPolicies);
            if (result.isChanged()) {
                log.info("[mart] SQL 改写生效 tables={} rows={} cols={}",
                        tables, result.getAppliedRows().size(), result.getAppliedColumns().size());
                return result.getSql();
            }
        } catch (Exception e) {
            log.warn("[mart] SQL 权限改写异常，使用原始 SQL: {}", e.getMessage());
        }
        return sql;
    }

    private static final Pattern TABLE_PATTERN_MART = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*)",
            Pattern.CASE_INSENSITIVE);

    private Set<String> extractSimpleTables(String sql) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN_MART.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (StrUtil.isNotBlank(table)) {
                int dot = table.lastIndexOf('.');
                tables.add(dot >= 0 ? table.substring(dot + 1) : table);
            }
        }
        return tables;
    }

    // ==================== 私有方法 ====================

    private void validateModel(MartModel model) {
        if (StrUtil.isBlank(model.getModelName()) || StrUtil.isBlank(model.getModelCode())) {
            throw new BusinessException("模型名称与编码不能为空");
        }
    }

    private ModelDto toModelDto(MartModel model) {
        ModelDto dto = new ModelDto();
        dto.setId(model.getId());
        dto.setModelName(model.getModelName());
        dto.setModelCode(model.getModelCode());
        dto.setModelType(model.getModelType());
        dto.setDatasourceId(model.getDatasourceId());
        dto.setFactTable(model.getFactTable());
        dto.setDescription(model.getDescription());
        dto.setStatus(model.getStatus());
        dto.setVersion(model.getVersion());
        dto.setProjectGroupId(model.getProjectGroupId());
        dto.setLayerId(model.getLayerId());
        return dto;
    }

    private MetricDto toMetricDto(MartMetric metric) {
        MetricDto dto = new MetricDto();
        dto.setId(metric.getId());
        dto.setMetricName(metric.getMetricName());
        dto.setMetricCode(metric.getMetricCode());
        dto.setMetricType(metric.getMetricType());
        dto.setExpression(metric.getExpression());
        dto.setDataType(metric.getDataType());
        dto.setUnit(metric.getUnit());
        dto.setCategoryId(metric.getCategoryId());
        dto.setModelId(metric.getModelId());
        dto.setDescription(metric.getDescription());
        return dto;
    }

    private DimensionDto toDimensionDto(MartDimension dimension) {
        DimensionDto dto = new DimensionDto();
        dto.setId(dimension.getId());
        dto.setDimName(dimension.getDimName());
        dto.setDimCode(dimension.getDimCode());
        dto.setDimType(dimension.getDimType());
        dto.setModelId(dimension.getModelId());
        dto.setSourceTable(dimension.getSourceTable());
        dto.setSourceColumn(dimension.getSourceColumn());
        dto.setDescription(dimension.getDescription());
        return dto;
    }
}
