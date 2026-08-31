package com.datakhaos.visual.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.mart.api.model.DimensionDto;
import com.datakhaos.mart.api.model.MetricDto;
import com.datakhaos.mart.api.service.MartApiClient;
import com.datakhaos.visual.dto.AdhocExecuteResponse;
import com.datakhaos.visual.dto.AdhocQueryRequest;
import com.datakhaos.visual.dto.DatasetChartQueryRequest;
import com.datakhaos.visual.dto.DatasetDto;
import com.datakhaos.visual.entity.VisualDataset;
import com.datakhaos.visual.mapper.VisualDatasetMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 数据集服务
 */
@Service
@RequiredArgsConstructor
public class DatasetService {

    private final VisualDatasetMapper datasetMapper;
    private final VisualService visualService;
    private final DatasourceApiClient datasourceApiClient;
    private final MartApiClient martApiClient;
    private final ObjectMapper objectMapper;

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_PUBLISHED = "PUBLISHED";

    private static final int DEFAULT_CHART_LIMIT = 1000;
    private static final int MAX_CHART_LIMIT = 10000;

    /**
     * 分页查询数据集列表
     */
    public Page<VisualDataset> page(Page<VisualDataset> page, String keyword, String datasetType, String status, String orgId) {
        LambdaQueryWrapper<VisualDataset> wrapper = new LambdaQueryWrapper<VisualDataset>()
                .like(StringUtils.hasText(keyword), VisualDataset::getName, keyword)
                .eq(StringUtils.hasText(datasetType), VisualDataset::getDatasetType, datasetType)
                .eq(StringUtils.hasText(status), VisualDataset::getStatus, status)
                .eq(StringUtils.hasText(orgId), VisualDataset::getOrgId, orgId)
                .orderByDesc(VisualDataset::getUpdateTime);
        return datasetMapper.selectPage(page, wrapper);
    }

    public VisualDataset getById(String id) {
        return datasetMapper.selectById(id);
    }

    /**
     * 创建数据集(草稿)
     */
    @Transactional
    public String create(DatasetDto dto) {
        VisualDataset entity = new VisualDataset();
        copyToEntity(dto, entity);
        entity.setId(UUID.randomUUID().toString().replace("-", ""));
        entity.setStatus(STATUS_DRAFT);
        entity.setVersion(1);
        entity.setDeleted(0);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());
        datasetMapper.insert(entity);
        return entity.getId();
    }

    /**
     * 更新数据集
     */
    @Transactional
    public void update(String id, DatasetDto dto) {
        VisualDataset entity = datasetMapper.selectById(id);
        if (entity == null) throw new RuntimeException("数据集不存在: " + id);
        copyToEntity(dto, entity);
        entity.setUpdateTime(LocalDateTime.now());
        datasetMapper.updateById(entity);
    }

    public void delete(String id) {
        datasetMapper.deleteById(id);
    }

    /**
     * 发布数据集
     */
    @Transactional
    public Integer publish(String id, String remark) {
        VisualDataset entity = datasetMapper.selectById(id);
        if (entity == null) throw new RuntimeException("数据集不存在: " + id);
        entity.setStatus(STATUS_PUBLISHED);
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setUpdateTime(LocalDateTime.now());
        datasetMapper.updateById(entity);
        return entity.getVersion();
    }

    /**
     * 下线数据集
     */
    @Transactional
    public void unpublish(String id) {
        VisualDataset entity = datasetMapper.selectById(id);
        if (entity == null) throw new RuntimeException("数据集不存在: " + id);
        entity.setStatus("OFFLINE");
        entity.setUpdateTime(LocalDateTime.now());
        datasetMapper.updateById(entity);
    }

    /**
     * 测试SQL查询并返回字段信息
     */
    public DatasetDto.DatasetPreviewResult preview(String datasourceId, String querySql) {
        DatasetDto.DatasetPreviewResult result = new DatasetDto.DatasetPreviewResult();

        try {
            AdhocQueryRequest request = new AdhocQueryRequest();
            request.setDatasourceId(datasourceId);
            request.setSql(querySql);
            var adhocResult = visualService.executeAdhoc(request);

            if (adhocResult != null && adhocResult.getResult() != null) {
                var queryResult = adhocResult.getResult();
                if (queryResult.getColumns() != null) {
                    List<String> columns = new ArrayList<>();
                    queryResult.getColumns().forEach(c -> columns.add(c.getColumnName()));
                    result.setColumns(columns);
                }
                result.setRows(queryResult.getRows() != null ? queryResult.getRows() : new ArrayList<>());
            }
        } catch (Exception e) {
            result.setColumns(new ArrayList<>());
            result.setRows(new ArrayList<>());
        }

        return result;
    }

    /**
     * 根据模型自动提取字段定义：将模型的指标（METRIC）和维度（DIMENSION）转化为数据集字段列表。
     * 优先通过 MartApiClient 拉取；失败时返回空列表（不阻断主流程）。
     */
    public List<DatasetDto.DatasetFieldDto> extractFieldsFromModel(String modelId) {
        if (StrUtil.isBlank(modelId)) {
            return new ArrayList<>();
        }
        List<DatasetDto.DatasetFieldDto> fields = new ArrayList<>();
        try {
            // 拉取维度
            List<DimensionDto> dimensions = martApiClient.listDimensions(modelId);
            int order = 1;
            for (DimensionDto dim : dimensions) {
                DatasetDto.DatasetFieldDto f = new DatasetDto.DatasetFieldDto();
                f.setId(dim.getId());
                f.setFieldCode(dim.getDimCode());
                f.setFieldName(dim.getDimName());
                f.setFieldType("DIMENSION");
                f.setDataType(mapDimDataType(dim.getDimType()));
                f.setSortOrder(order++);
                fields.add(f);
            }
            // 拉取指标
            List<MetricDto> metrics = martApiClient.listMetrics(modelId);
            for (MetricDto m : metrics) {
                DatasetDto.DatasetFieldDto f = new DatasetDto.DatasetFieldDto();
                f.setId(m.getId());
                f.setFieldCode(m.getMetricCode());
                f.setFieldName(m.getMetricName());
                f.setFieldType("METRIC");
                f.setDataType(m.getDataType() != null ? m.getDataType() : "DECIMAL");
                f.setAggType(inferAggType(m.getMetricType()));
                f.setSortOrder(order++);
                fields.add(f);
            }
        } catch (Exception e) {
            // 静默处理，不阻断主流程
        }
        return fields;
    }

    private String mapDimDataType(String dimType) {
        if ("TIME".equalsIgnoreCase(dimType)) return "DATE";
        return "STRING";
    }

    private String inferAggType(String metricType) {
        if ("DERIVED".equalsIgnoreCase(metricType)) return "SUM";
        return "SUM";
    }

    private void copyToEntity(DatasetDto dto, VisualDataset entity) {
        entity.setName(dto.getName());
        entity.setCode(dto.getCode());
        entity.setDescription(dto.getDescription());
        entity.setDatasetType(dto.getDatasetType());
        entity.setDatasourceId(dto.getDatasourceId());
        entity.setQuerySql(dto.getQuerySql());
        entity.setModelId(dto.getModelId());
        entity.setRefreshInterval(dto.getRefreshInterval());
        entity.setVisibility(dto.getVisibility());

        try {
            if (dto.getFields() != null) {
                entity.setFieldsJson(objectMapper.writeValueAsString(dto.getFields()));
            }
            if (dto.getVariables() != null) {
                entity.setVariablesJson(objectMapper.writeValueAsString(dto.getVariables()));
            }
        } catch (Exception e) {
            throw new RuntimeException("序列化字段定义失败", e);
        }
    }

    /**
     * 解析字段JSON
     */
    public List<DatasetDto.DatasetFieldDto> parseFields(String json) {
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<DatasetDto.DatasetFieldDto>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ==================== 图表绘制页（BI Chart Builder） ====================

    /**
     * 已发布数据集列表（图表绘制页左侧资产池），附带数据源类型供联查兼容性判断。
     */
    public List<DatasetDto> listPublished() {
        List<VisualDataset> list = datasetMapper.selectList(new LambdaQueryWrapper<VisualDataset>()
                .eq(VisualDataset::getStatus, STATUS_PUBLISHED)
                .orderByDesc(VisualDataset::getUpdateTime));
        Map<String, String> typeCache = new HashMap<>();
        List<DatasetDto> result = new ArrayList<>();
        for (VisualDataset e : list) {
            DatasetDto dto = toDto(e);
            if (StrUtil.isNotBlank(e.getDatasourceId())) {
                dto.setDatasourceType(typeCache.computeIfAbsent(e.getDatasourceId(),
                        k -> datasourceApiClient.datasourceType(k)));
            }
            result.add(dto);
        }
        return result;
    }

    /**
     * 图表聚合查询：根据数据集 + 维度/指标/筛选/排序生成 SQL 并执行（复用即席分析的安全审核与权限校验）。
     * SELECT d1, d2, AGG(m) AS m FROM (数据集SQL) t WHERE ... GROUP BY ... ORDER BY ... LIMIT n
     */
    public DatasetDto.DatasetChartQueryResult queryChart(DatasetChartQueryRequest req) {
        if (req == null || StrUtil.isBlank(req.getDatasetId())) {
            throw new BusinessException("数据集ID不能为空");
        }
        VisualDataset ds = datasetMapper.selectById(req.getDatasetId());
        if (ds == null) throw new BusinessException("数据集不存在: " + req.getDatasetId());
        if (StrUtil.isBlank(ds.getQuerySql())) throw new BusinessException("数据集未定义查询SQL");
        if (StrUtil.isBlank(ds.getDatasourceId())) throw new BusinessException("数据集未关联数据源");

        // 字段白名单：维度/指标/筛选/排序引用的字段必须来自数据集定义
        Set<String> validCodes = new HashSet<>();
        for (DatasetDto.DatasetFieldDto f : parseFields(ds.getFieldsJson())) {
            if (StrUtil.isNotBlank(f.getFieldCode())) validCodes.add(f.getFieldCode());
        }

        Set<String> usedAliases = new HashSet<>();
        List<String> selects = new ArrayList<>();
        List<String> groupBys = new ArrayList<>();
        Set<String> orderable = new HashSet<>();

        if (req.getDimensions() != null) {
            for (DatasetChartQueryRequest.FieldRef d : req.getDimensions()) {
                requireField(validCodes, d.getFieldCode());
                String col = safeCol(d.getFieldCode());
                selects.add(col);
                groupBys.add(col);
                orderable.add(col);
                usedAliases.add(col);
            }
        }
        if (req.getMetrics() != null) {
            for (DatasetChartQueryRequest.MetricRef m : req.getMetrics()) {
                requireField(validCodes, m.getFieldCode());
                String col = safeCol(m.getFieldCode());
                String agg = aggSql(m.getAggType(), col);
                String alias = uniqueAlias(col, usedAliases);
                selects.add(agg + " AS " + alias);
                orderable.add(alias);
                usedAliases.add(alias);
            }
        }
        if (selects.isEmpty()) {
            throw new BusinessException("请至少选择一个维度或指标");
        }

        List<String> where = new ArrayList<>();
        if (req.getFilters() != null) {
            for (DatasetChartQueryRequest.FilterRef f : req.getFilters()) {
                requireField(validCodes, f.getFieldCode());
                String cond = buildCondition(safeCol(f.getFieldCode()), f);
                if (cond != null) where.add(cond);
            }
        }

        List<String> orders = new ArrayList<>();
        if (req.getSorts() != null) {
            for (DatasetChartQueryRequest.SortRef s : req.getSorts()) {
                requireField(validCodes, s.getFieldCode());
                String col = safeCol(s.getFieldCode());
                if (!orderable.contains(col)) {
                    // 非投影列排序：包一层聚合函数兜底（仅指标语义时合理）
                    continue;
                }
                orders.add(col + ("DESC".equalsIgnoreCase(s.getDirection()) ? " DESC" : " ASC"));
            }
        }

        int limit = req.getLimit() == null || req.getLimit() <= 0 ? DEFAULT_CHART_LIMIT
                : Math.min(req.getLimit(), MAX_CHART_LIMIT);

        String sql = "SELECT " + String.join(", ", selects)
                + " FROM (" + stripSemicolon(ds.getQuerySql()) + ") t"
                + (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where))
                + (groupBys.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupBys))
                + (orders.isEmpty() ? "" : " ORDER BY " + String.join(", ", orders))
                + " LIMIT " + limit;

        AdhocQueryRequest adhoc = new AdhocQueryRequest();
        adhoc.setDatasourceId(ds.getDatasourceId());
        adhoc.setSql(sql);
        AdhocExecuteResponse resp = visualService.executeAdhoc(adhoc);

        DatasetDto.DatasetChartQueryResult result = new DatasetDto.DatasetChartQueryResult();
        result.setSql(sql);
        if (resp != null) {
            result.setResult(resp.getResult());
            result.setTruncated(resp.isTruncated());
            result.setOriginalRowCount(resp.getOriginalRowCount());
        }
        return result;
    }

    /** 字段白名单校验 */
    private void requireField(Set<String> validCodes, String fieldCode) {
        if (StrUtil.isBlank(fieldCode) || !validCodes.contains(fieldCode)) {
            throw new BusinessException("字段不在数据集定义中: " + fieldCode);
        }
    }

    /** 字段名清洗：仅允许字母数字下划线与中文，防注入 */
    private String safeCol(String col) {
        return col.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
    }

    /** 值转义：单引号翻倍 */
    private String esc(String v) {
        return v == null ? "" : v.replace("'", "''");
    }

    /** 别名去重：同名列追加序号，避免 SELECT 投影重名 */
    private String uniqueAlias(String base, Set<String> used) {
        if (!used.contains(base)) return base;
        int i = 2;
        while (used.contains(base + "_" + i)) i++;
        return base + "_" + i;
    }

    /** 去掉 SQL 尾部分号/空白（作为子查询包装时必需） */
    private String stripSemicolon(String sql) {
        String s = sql.trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    /** 聚合函数 SQL 片段 */
    private String aggSql(String aggType, String col) {
        String agg = aggType == null ? "SUM" : aggType.toUpperCase();
        switch (agg) {
            case "AVG": return "AVG(" + col + ")";
            case "COUNT": return "COUNT(" + col + ")";
            case "COUNT_DISTINCT": return "COUNT(DISTINCT " + col + ")";
            case "MAX": return "MAX(" + col + ")";
            case "MIN": return "MIN(" + col + ")";
            default: return "SUM(" + col + ")";
        }
    }

    /** 筛选条件 → SQL 片段（值统一转义；无有效值返回 null） */
    private String buildCondition(String col, DatasetChartQueryRequest.FilterRef f) {
        String op = f.getOperator() == null ? "EQ" : f.getOperator().toUpperCase();
        List<String> vals = new ArrayList<>();
        if (f.getValues() != null) {
            for (Object v : f.getValues()) {
                if (v != null && StrUtil.isNotBlank(String.valueOf(v))) vals.add(String.valueOf(v));
            }
        }
        switch (op) {
            case "EQ": return vals.isEmpty() ? null : col + " = '" + esc(vals.get(0)) + "'";
            case "NE": return vals.isEmpty() ? null : col + " <> '" + esc(vals.get(0)) + "'";
            case "GT": return vals.isEmpty() ? null : col + " > '" + esc(vals.get(0)) + "'";
            case "GTE": return vals.isEmpty() ? null : col + " >= '" + esc(vals.get(0)) + "'";
            case "LT": return vals.isEmpty() ? null : col + " < '" + esc(vals.get(0)) + "'";
            case "LTE": return vals.isEmpty() ? null : col + " <= '" + esc(vals.get(0)) + "'";
            case "LIKE": return vals.isEmpty() ? null : col + " LIKE '%" + esc(vals.get(0)) + "%'";
            case "IN": return vals.isEmpty() ? null : col + inClause(false, vals);
            case "NOT_IN": return vals.isEmpty() ? null : col + inClause(true, vals);
            case "BETWEEN": return vals.size() < 2 ? null
                    : col + " BETWEEN '" + esc(vals.get(0)) + "' AND '" + esc(vals.get(1)) + "'";
            default: return null;
        }
    }

    private String inClause(boolean negated, List<String> vals) {
        List<String> quoted = new ArrayList<>();
        for (String v : vals) quoted.add("'" + esc(v) + "'");
        return (negated ? " NOT" : "") + " IN (" + String.join(", ", quoted) + ")";
    }

    /** 实体 → DTO（含字段与变量解析） */
    public DatasetDto toDto(VisualDataset entity) {
        if (entity == null) return null;
        DatasetDto dto = new DatasetDto();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setCode(entity.getCode());
        dto.setDescription(entity.getDescription());
        dto.setDatasetType(entity.getDatasetType());
        dto.setDatasourceId(entity.getDatasourceId());
        dto.setQuerySql(entity.getQuerySql());
        dto.setModelId(entity.getModelId());
        dto.setRefreshInterval(entity.getRefreshInterval());
        dto.setVisibility(entity.getVisibility());
        dto.setStatus(entity.getStatus());
        dto.setVersion(entity.getVersion());
        dto.setFields(parseFields(entity.getFieldsJson()));
        try {
            if (entity.getVariablesJson() != null) {
                dto.setVariables(List.of(objectMapper.readValue(entity.getVariablesJson(),
                        DatasetDto.DatasetVariableDto[].class)));
            }
        } catch (Exception e) { /* ignore */ }
        return dto;
    }
}
