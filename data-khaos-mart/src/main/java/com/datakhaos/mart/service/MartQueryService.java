package com.datakhaos.mart.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.mart.dto.MartQueryRequest;
import com.datakhaos.mart.dto.MartQueryResult;
import com.datakhaos.mart.entity.MartDimension;
import com.datakhaos.mart.entity.MartMetric;
import com.datakhaos.mart.entity.MartModel;
import com.datakhaos.mart.entity.MartModelRel;
import com.datakhaos.mart.mapper.MartDimensionMapper;
import com.datakhaos.mart.mapper.MartMetricMapper;
import com.datakhaos.mart.mapper.MartModelMapper;
import com.datakhaos.mart.mapper.MartModelRelMapper;
import com.datakhaos.permission.api.model.UserPermissionDto;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.permission.api.service.PermissionConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据集市语义查询服务：基于「模型 + 指标 + 维度 + 筛选 + 排序」生成 SQL 并执行。
 * 串联指标体系（mart）与 BI 图表画布，是画布语义层驱动的核心桥梁。
 *
 * 设计要点：
 *  - 指标聚合语义内嵌于 metric.expression（ATOMIC 直接使用；DERIVED 引用原子指标编码，两段式子查询）。
 *  - 维度通过 MartModelRel 自动 JOIN 事实表；TIME 维度支持年/月/日粒度（按数据源方言分派）。
 *  - 所有标识符经 safeCol 清洗、值经 esc 转义，指标/维度编码走模型白名单校验，防注入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MartQueryService {

    private final MartModelMapper modelMapper;
    private final MartMetricMapper metricMapper;
    private final MartDimensionMapper dimensionMapper;
    private final MartModelRelMapper modelRelMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final PermissionApiClient permissionApiClient;

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 10000;
    private static final String FACT_ALIAS = "f";

    /**
     * 语义查询主入口。
     */
    public MartQueryResult query(MartQueryRequest req) {
        if (req == null || StrUtil.isBlank(req.getModelId())) {
            throw new BusinessException("模型ID不能为空");
        }
        if (req.getMetrics() == null || req.getMetrics().isEmpty()) {
            throw new BusinessException("请至少选择一个指标");
        }

        MartModel model = loadModel(req.getModelId());
        String dsType = datasourceApiClient.datasourceType(model.getDatasourceId());

        Map<String, MartMetric> metricMap = loadMetrics(model.getId());
        Map<String, MartDimension> dimMap = loadDimensions(model.getId());
        List<MartModelRel> rels = loadRels(model.getId());
        String factTable = resolveFactTable(model, rels);

        // 指标分类：原子 / 派生
        List<MartMetric> atomic = new ArrayList<>();
        List<MartMetric> derived = new ArrayList<>();
        for (MartQueryRequest.MetricRef m : req.getMetrics()) {
            MartMetric mm = metricMap.get(m.getMetricCode());
            if (mm == null) {
                throw new BusinessException("指标不在模型中: " + m.getMetricCode());
            }
            if ("DERIVED".equalsIgnoreCase(mm.getMetricType())) {
                derived.add(mm);
            } else {
                atomic.add(mm);
            }
        }

        // 维度解析 + 表别名分配
        QueryContext ctx = new QueryContext(factTable, dsType, rels);
        List<DimProjection> dims = new ArrayList<>();
        if (req.getDimensions() != null) {
            for (MartQueryRequest.DimRef d : req.getDimensions()) {
                MartDimension dim = dimMap.get(d.getDimCode());
                if (dim == null) {
                    throw new BusinessException("维度不在模型中: " + d.getDimCode());
                }
                dims.add(resolveDim(dim, d, ctx));
            }
        }

        // 投影/分组列
        List<String> dimSelects = new ArrayList<>();
        List<String> groupBys = new ArrayList<>();
        Set<String> projectionAliases = new HashSet<>();
        for (DimProjection p : dims) {
            dimSelects.add(p.selectExpr);
            groupBys.add(p.groupByExpr);
            registrationAlias(projectionAliases, p.alias);
        }
        List<String> atomicSelects = new ArrayList<>();
        for (MartMetric mm : atomic) {
            String alias = safeCol(mm.getMetricCode());
            registrationAlias(projectionAliases, alias);
            atomicSelects.add(mm.getExpression() + " AS " + alias);
        }

        // 筛选（作用于内层，用原始列 + 值转义）
        List<String> where = new ArrayList<>();
        if (req.getFilters() != null) {
            for (MartQueryRequest.FilterRef f : req.getFilters()) {
                MartDimension dim = dimMap.get(f.getDimCode());
                if (dim == null) {
                    throw new BusinessException("筛选维度不在模型中: " + f.getDimCode());
                }
                String colRef = colRef(dim.getSourceTable(), dim.getSourceColumn(), ctx);
                String cond = buildCondition(colRef, f);
                if (cond != null) where.add(cond);
            }
        }

        // 排序（仅投影别名）
        List<String> orders = new ArrayList<>();
        if (req.getSorts() != null) {
            for (MartQueryRequest.SortRef s : req.getSorts()) {
                String code = safeCol(s.getCode());
                if (!projectionAliases.contains(code)) continue;
                orders.add(code + ("DESC".equalsIgnoreCase(s.getDirection()) ? " DESC" : " ASC"));
            }
        }

        int limit = req.getLimit() == null || req.getLimit() <= 0 ? DEFAULT_LIMIT
                : Math.min(req.getLimit(), MAX_LIMIT);

        // 内层 SELECT（维度 + 原子指标）
        List<String> innerSelects = new ArrayList<>(dimSelects);
        innerSelects.addAll(atomicSelects);

        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String groupSql = groupBys.isEmpty() ? "" : " GROUP BY " + String.join(", ", groupBys);
        String fromSql = " FROM " + safeCol(factTable) + " " + FACT_ALIAS + buildJoins(ctx);

        StringBuilder sql = new StringBuilder();
        if (derived.isEmpty()) {
            // 单段式
            sql.append("SELECT ").append(String.join(", ", innerSelects))
                    .append(fromSql).append(whereSql).append(groupSql);
        } else {
            // 两段式：内层聚合原子指标，外层计算派生指标
            List<String> outerSelects = new ArrayList<>();
            for (DimProjection p : dims) outerSelects.add(p.alias);
            for (MartMetric mm : atomic) outerSelects.add(safeCol(mm.getMetricCode()));
            for (MartMetric mm : derived) {
                registrationAlias(projectionAliases, safeCol(mm.getMetricCode()));
                outerSelects.add(mm.getExpression() + " AS " + safeCol(mm.getMetricCode()));
            }
            sql.append("SELECT ").append(String.join(", ", outerSelects))
                    .append(" FROM (SELECT ").append(String.join(", ", innerSelects))
                    .append(fromSql).append(whereSql).append(groupSql)
                    .append(") t");
        }

        if (!orders.isEmpty()) sql.append(" ORDER BY ").append(String.join(", ", orders));
        sql.append(" LIMIT ").append(limit);

        // 执行
        MartQueryResult result = new MartQueryResult();
        result.setSql(sql.toString());
        R<QueryResult> resp = datasourceApiClient.executeRaw(model.getDatasourceId(), sql.toString());
        if (resp != null && resp.getCode() == 0 && resp.getData() != null) {
            QueryResult qr = resp.getData();
            result.setResult(qr);
            int rows = qr.getRows() == null ? 0 : qr.getRows().size();
            result.setOriginalRowCount(qr.getRowCount() == null ? rows : qr.getRowCount());
            result.setTruncated(rows >= limit);
        } else if (resp != null && resp.getCode() != 0) {
            throw new BusinessException("查询执行失败: " + resp.getMsg());
        }
        return result;
    }

    /**
     * 维度取值（枚举筛选器下拉用）：SELECT DISTINCT sourceColumn FROM sourceTable。
     */
    public List<String> dimensionValues(String dimId, int limit) {
        requireBrowseCap();
        MartDimension dim = dimensionMapper.selectById(dimId);
        if (dim == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "维度不存在: " + dimId);
        }
        if (StrUtil.isBlank(dim.getSourceTable()) || StrUtil.isBlank(dim.getSourceColumn())) {
            return new ArrayList<>();
        }
        MartModel model = modelMapper.selectById(dim.getModelId());
        if (model == null || StrUtil.isBlank(model.getDatasourceId())) {
            throw new BusinessException("维度所属模型未绑定数据源");
        }
        checkModelGroup(model);
        int n = limit <= 0 || limit > MAX_LIMIT ? DEFAULT_LIMIT : limit;
        String sql = "SELECT DISTINCT " + safeCol(dim.getSourceColumn())
                + " FROM " + safeCol(dim.getSourceTable()) + " LIMIT " + n;
        R<QueryResult> resp = datasourceApiClient.executeRaw(model.getDatasourceId(), sql);
        List<String> values = new ArrayList<>();
        if (resp != null && resp.getCode() == 0 && resp.getData() != null
                && resp.getData().getRows() != null) {
            for (Map<String, Object> row : resp.getData().getRows()) {
                Object v = row.values().iterator().hasNext() ? row.values().iterator().next() : null;
                if (v != null) values.add(String.valueOf(v));
            }
        }
        return values;
    }

    // ==================== 权限 ====================

    private MartModel loadModel(String modelId) {
        requireBrowseCap();
        MartModel model = modelMapper.selectById(modelId);
        if (model == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模型不存在: " + modelId);
        }
        if (model.getStatus() == null || model.getStatus() != 1) {
            throw new BusinessException("模型未发布，无法查询: " + model.getModelName());
        }
        checkModelGroup(model);
        return model;
    }

    private void requireBrowseCap() {
        if (MetadataHolder.isSuperAdmin()) return;
        String userId = MetadataHolder.getUserId();
        if (StrUtil.isBlank(userId)) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        UserPermissionDto perm = permissionApiClient.getUserPermission(userId);
        List<String> caps = perm.getCapabilityFlags() == null ? List.of() : perm.getCapabilityFlags();
        if (!caps.contains(PermissionConstants.CAP_MODEL_BROWSE)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无「模型浏览」操作权限");
        }
    }

    private void checkModelGroup(MartModel model) {
        if (MetadataHolder.isSuperAdmin()) return;
        String userId = MetadataHolder.getUserId();
        if (StrUtil.isBlank(userId)) throw new BusinessException(ResultCode.UNAUTHORIZED, "未登录");
        UserPermissionDto perm = permissionApiClient.getUserPermission(userId);
        String pg = perm.getProjectGroupId();
        if (StrUtil.isBlank(pg) || !pg.equals(model.getProjectGroupId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该项目组的模型");
        }
    }

    private Map<String, MartMetric> loadMetrics(String modelId) {
        Map<String, MartMetric> map = new HashMap<>();
        for (MartMetric m : metricMapper.selectList(new LambdaQueryWrapper<MartMetric>()
                .eq(MartMetric::getModelId, modelId).eq(MartMetric::getStatus, 1))) {
            if (StrUtil.isNotBlank(m.getMetricCode())) map.put(m.getMetricCode(), m);
        }
        return map;
    }

    private Map<String, MartDimension> loadDimensions(String modelId) {
        Map<String, MartDimension> map = new HashMap<>();
        for (MartDimension d : dimensionMapper.selectList(new LambdaQueryWrapper<MartDimension>()
                .eq(MartDimension::getModelId, modelId).eq(MartDimension::getStatus, 1))) {
            if (StrUtil.isNotBlank(d.getDimCode())) map.put(d.getDimCode(), d);
        }
        return map;
    }

    private List<MartModelRel> loadRels(String modelId) {
        return modelRelMapper.selectList(new LambdaQueryWrapper<MartModelRel>()
                .eq(MartModelRel::getModelId, modelId).orderByAsc(MartModelRel::getCreateTime));
    }

    private String resolveFactTable(MartModel model, List<MartModelRel> rels) {
        if (StrUtil.isNotBlank(model.getFactTable())) return model.getFactTable();
        return rels.stream().map(MartModelRel::getFactTable)
                .filter(StrUtil::isNotBlank).findFirst()
                .orElseThrow(() -> new BusinessException("模型未配置主事实表"));
    }

    // ==================== SQL 片段 ====================

    /** 维度投影解析（列引用 + 时间粒度 + 别名） */
    private DimProjection resolveDim(MartDimension dim, MartQueryRequest.DimRef ref, QueryContext ctx) {
        String column = StrUtil.isNotBlank(ref.getLevelColumn()) ? ref.getLevelColumn() : dim.getSourceColumn();
        if (StrUtil.isBlank(column)) {
            throw new BusinessException("维度未配置来源列: " + dim.getDimName());
        }
        String colRef = colRef(dim.getSourceTable(), column, ctx);
        // 别名固定为维度编码（不含粒度后缀），保证与前端 fieldCode 一致，粒度仅作用于 SELECT/GROUP BY 表达式
        String alias = safeCol(dim.getDimCode());

        boolean hasGrain = "TIME".equalsIgnoreCase(dim.getDimType()) && StrUtil.isNotBlank(ref.getGrain());
        String groupByExpr = hasGrain ? timeExpr(colRef, ref.getGrain(), ctx.dsType) : colRef;
        String selectExpr = (hasGrain ? timeExpr(colRef, ref.getGrain(), ctx.dsType) : colRef) + " AS " + alias;
        return new DimProjection(selectExpr, groupByExpr, alias);
    }

    /** 返回带表别名的列引用（顺带分配维度表别名） */
    private String colRef(String table, String column, QueryContext ctx) {
        String col = safeCol(column);
        if (StrUtil.isBlank(table) || table.equals(ctx.factTable)) {
            return FACT_ALIAS + "." + col;
        }
        String alias = ctx.tableAlias.computeIfAbsent(table, k -> "d" + (++ctx.aliasIdx));
        if (!ctx.relByTable.containsKey(table)) {
            throw new BusinessException("维度表缺少关联关系，无法 JOIN: " + table);
        }
        return alias + "." + col;
    }

    /** 生成 JOIN 片段（按表别名加入顺序去重） */
    private String buildJoins(QueryContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : ctx.tableAlias.entrySet()) {
            MartModelRel rel = ctx.relByTable.get(e.getKey());
            String joinType = rel.getJoinType() == null ? "LEFT" : rel.getJoinType().toUpperCase();
            String jk = safeCol(rel.getJoinKey());
            if (StrUtil.isBlank(jk)) {
                throw new BusinessException("关联关系缺少 joinKey: " + e.getKey());
            }
            sb.append(" ").append(joinType).append(" JOIN ").append(safeCol(e.getKey()))
                    .append(" ").append(e.getValue())
                    .append(" ON ").append(FACT_ALIAS).append(".").append(jk)
                    .append(" = ").append(e.getValue()).append(".").append(jk);
        }
        return sb.toString();
    }

    /** 时间粒度表达式（按数据源方言分派） */
    private String timeExpr(String col, String grain, String dsType) {
        String g = grain == null ? "D" : grain.toUpperCase();
        String t = dsType == null ? "" : dsType.toUpperCase();
        boolean hive = t.contains("HIVE");
        boolean click = t.contains("CLICK");
        switch (g) {
            case "Y":
                if (click) return "toString(toYear(" + col + "))";
                if (hive) return "date_format(" + col + ", 'yyyy')";
                return "DATE_FORMAT(" + col + ", '%Y')";
            case "M":
                if (click) return "formatDateTime(" + col + ", '%Y-%m')";
                if (hive) return "date_format(" + col + ", 'yyyy-MM')";
                return "DATE_FORMAT(" + col + ", '%Y-%m')";
            case "D":
            default:
                if (click) return "formatDateTime(" + col + ", '%Y-%m-%d')";
                if (hive) return "date_format(" + col + ", 'yyyy-MM-dd')";
                return "DATE_FORMAT(" + col + ", '%Y-%m-%d')";
        }
    }

    private void registrationAlias(Set<String> used, String alias) {
        if (alias == null || alias.isEmpty()) return;
        if (!used.add(alias)) {
            throw new BusinessException("投影别名冲突，请调整指标/维度编码: " + alias);
        }
    }

    private String buildCondition(String col, MartQueryRequest.FilterRef f) {
        String op = f.getOperator() == null ? "EQ" : f.getOperator().toUpperCase();
        List<String> vals = new ArrayList<>();
        if (f.getValues() != null) {
            for (String v : f.getValues()) {
                if (StrUtil.isNotBlank(v)) vals.add(v);
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

    private String esc(String v) {
        return v == null ? "" : v.replace("'", "''");
    }

    private String safeCol(String col) {
        return col == null ? "" : col.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
    }

    // ==================== 内部结构 ====================

    /** 维度投影结果 */
    private static class DimProjection {
        final String selectExpr;
        final String groupByExpr;
        final String alias;
        DimProjection(String selectExpr, String groupByExpr, String alias) {
            this.selectExpr = selectExpr;
            this.groupByExpr = groupByExpr;
            this.alias = alias;
        }
    }

    /** 查询上下文（表别名分配 + 关联映射） */
    private static class QueryContext {
        final String factTable;
        final String dsType;
        final Map<String, MartModelRel> relByTable = new LinkedHashMap<>();
        final Map<String, String> tableAlias = new LinkedHashMap<>();
        int aliasIdx = 0;

        QueryContext(String factTable, String dsType, List<MartModelRel> rels) {
            this.factTable = factTable;
            this.dsType = dsType;
            for (MartModelRel r : rels) {
                if (StrUtil.isNotBlank(r.getDimTable()) && !relByTable.containsKey(r.getDimTable())) {
                    relByTable.put(r.getDimTable(), r);
                }
            }
        }
    }
}