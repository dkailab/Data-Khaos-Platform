package com.datakhaos.visual.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.common.security.SqlAuditUtil;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.ColumnPolicy;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RewriteResult;
import com.datakhaos.common.security.rewrite.SqlRewriteEngine.RowPolicy;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.permission.api.service.PermissionApiClient;
import com.datakhaos.visual.dto.AdhocExecuteResponse;
import com.datakhaos.visual.dto.AdhocQueryRequest;
import com.datakhaos.visual.dto.AdhocSaveRequest;
import com.datakhaos.visual.dto.SaveAsItemRequest;
import com.datakhaos.visual.entity.VisualAdhocHistory;
import com.datakhaos.visual.entity.VisualAdhocQuery;
import com.datakhaos.visual.entity.VisualBoard;
import com.datakhaos.visual.entity.VisualDashboard;
import com.datakhaos.visual.entity.VisualDashboardItem;
import com.datakhaos.visual.entity.VisualDashboardVersion;
import com.datakhaos.visual.mapper.VisualAdhocHistoryMapper;
import com.datakhaos.visual.mapper.VisualAdhocQueryMapper;
import com.datakhaos.visual.mapper.VisualBoardMapper;
import com.datakhaos.visual.mapper.VisualDashboardItemMapper;
import com.datakhaos.visual.mapper.VisualDashboardMapper;
import com.datakhaos.visual.mapper.VisualDashboardVersionMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 可视化服务：仪表板/组件管理与数据查询。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisualService {

    private final VisualDashboardMapper dashboardMapper;
    private final VisualDashboardItemMapper itemMapper;
    private final VisualDashboardVersionMapper versionMapper;
    private final VisualBoardMapper boardMapper;
    private final DatasourceApiClient datasourceApiClient;
    private final VisualAdhocQueryMapper adhocQueryMapper;
    private final VisualAdhocHistoryMapper adhocHistoryMapper;
    private final PermissionApiClient permissionApiClient;
    private final ObjectMapper objectMapper;

    /** 即席查询结果行数上限（防止大结果集拖垮前端/网络） */
    @Value("${visual.adhoc.max-rows:10000}")
    private int adhocMaxRows;

    /** 即席查询是否开启表权限校验 */
    @Value("${visual.adhoc.permission-check:true}")
    private boolean adhocPermissionCheck;

    // ==================== 仪表板 ====================

    public PageResult<VisualDashboard> dashboardPage(long current, long size, String keyword) {
        Page<VisualDashboard> page = dashboardMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VisualDashboard>()
                        .like(StrUtil.isNotBlank(keyword), VisualDashboard::getName, keyword)
                        .orderByDesc(VisualDashboard::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    public VisualDashboard getDashboard(String id) {
        VisualDashboard dashboard = dashboardMapper.selectById(id);
        if (dashboard == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "仪表板不存在: " + id);
        }
        return dashboard;
    }

    @Transactional(rollbackFor = Exception.class)
    public String createDashboard(VisualDashboard dashboard) {
        if (StrUtil.isBlank(dashboard.getName())) {
            throw new BusinessException("仪表板名称不能为空");
        }
        dashboard.setStatus(dashboard.getStatus() == null ? 1 : dashboard.getStatus());
        dashboard.setRefreshInterval(dashboard.getRefreshInterval() == null ? 60 : dashboard.getRefreshInterval());
        if (StrUtil.isBlank(dashboard.getCreateBy())) {
            dashboard.setCreateBy(MetadataHolder.getUserId());
        }
        dashboardMapper.insert(dashboard);
        return dashboard.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateDashboard(VisualDashboard dashboard) {
        if (StrUtil.isBlank(dashboard.getId())) {
            throw new BusinessException("仪表板ID不能为空");
        }
        getDashboard(dashboard.getId());
        dashboardMapper.updateById(dashboard);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDashboard(String id) {
        getDashboard(id);
        itemMapper.delete(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getDashboardId, id));
        boardMapper.delete(new LambdaQueryWrapper<VisualBoard>()
                .eq(VisualBoard::getDashboardId, id));
        versionMapper.delete(new LambdaQueryWrapper<VisualDashboardVersion>()
                .eq(VisualDashboardVersion::getDashboardId, id));
        dashboardMapper.deleteById(id);
    }

    // ==================== 版本控制 ====================

    /** 上线：生成当前草稿的快照版本，并置为已上线 */
    @Transactional(rollbackFor = Exception.class)
    public Integer publish(String dashboardId, String remark) {
        VisualDashboard dashboard = getDashboard(dashboardId);
        List<VisualDashboardItem> items = itemMapper.selectList(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getDashboardId, dashboardId));
        List<VisualBoard> boards = boardMapper.selectList(new LambdaQueryWrapper<VisualBoard>()
                .eq(VisualBoard::getDashboardId, dashboardId)
                .orderByAsc(VisualBoard::getSortOrder));
        int newVersion = (dashboard.getVersion() == null ? 0 : dashboard.getVersion()) + 1;

        VisualDashboardVersion version = new VisualDashboardVersion();
        version.setDashboardId(dashboardId);
        version.setVersion(newVersion);
        version.setName(dashboard.getName());
        version.setDescription(dashboard.getDescription());
        version.setLayout(dashboard.getLayout());
        version.setRefreshInterval(dashboard.getRefreshInterval());
        version.setItemsJson(writeJson(items));
        version.setBoardsJson(writeJson(boards));
        version.setRemark(remark);
        version.setCreateBy(MetadataHolder.getUserId());
        versionMapper.insert(version);

        VisualDashboard update = new VisualDashboard();
        update.setId(dashboardId);
        update.setVersion(newVersion);
        update.setStatus(2);
        dashboardMapper.updateById(update);
        return newVersion;
    }

    /** 下线：回到草稿状态 */
    @Transactional(rollbackFor = Exception.class)
    public void unpublish(String dashboardId) {
        getDashboard(dashboardId);
        VisualDashboard update = new VisualDashboard();
        update.setId(dashboardId);
        update.setStatus(1);
        dashboardMapper.updateById(update);
    }

    /** 版本列表 */
    public List<VisualDashboardVersion> versionList(String dashboardId) {
        getDashboard(dashboardId);
        return versionMapper.selectList(new LambdaQueryWrapper<VisualDashboardVersion>()
                .eq(VisualDashboardVersion::getDashboardId, dashboardId)
                .orderByDesc(VisualDashboardVersion::getVersion));
    }

    /** 版本快照详情 */
    public VisualDashboardVersion versionDetail(String versionId) {
        VisualDashboardVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "版本不存在: " + versionId);
        }
        return version;
    }

    /** 回滚到指定版本：用快照覆盖仪表板信息与组件 */
    @Transactional(rollbackFor = Exception.class)
    public void rollback(String dashboardId, String versionId) {
        VisualDashboard dashboard = getDashboard(dashboardId);
        VisualDashboardVersion version = versionDetail(versionId);
        if (!StrUtil.equals(version.getDashboardId(), dashboardId)) {
            throw new BusinessException("版本不属于该仪表板");
        }

        VisualDashboard update = new VisualDashboard();
        update.setId(dashboardId);
        update.setName(version.getName());
        update.setDescription(version.getDescription());
        update.setLayout(version.getLayout());
        update.setRefreshInterval(version.getRefreshInterval());
        update.setStatus(1);
        dashboardMapper.updateById(update);

        List<VisualDashboardItem> items = readItems(version.getItemsJson());
        itemMapper.delete(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getDashboardId, dashboardId));
        for (VisualDashboardItem item : items) {
            item.setId(null);
            item.setCreateTime(null);
            itemMapper.insert(item);
        }

        List<VisualBoard> boards = readBoards(version.getBoardsJson());
        boardMapper.delete(new LambdaQueryWrapper<VisualBoard>()
                .eq(VisualBoard::getDashboardId, dashboardId));
        for (VisualBoard board : boards) {
            board.setId(null);
            board.setCreateTime(null);
            boardMapper.insert(board);
        }
    }

    private List<VisualBoard> readBoards(String boardsJson) {
        if (StrUtil.isBlank(boardsJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(boardsJson, new TypeReference<List<VisualBoard>>() {
            });
        } catch (Exception e) {
            throw new BusinessException("解析分析板快照失败: " + e.getMessage());
        }
    }

    private String writeJson(List<?> items) {
        try {
            return objectMapper.writeValueAsString(items == null ? Collections.emptyList() : items);
        } catch (Exception e) {
            throw new BusinessException("生成版本快照失败: " + e.getMessage());
        }
    }

    private List<VisualDashboardItem> readItems(String itemsJson) {
        if (StrUtil.isBlank(itemsJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(itemsJson, new TypeReference<List<VisualDashboardItem>>() {
            });
        } catch (Exception e) {
            throw new BusinessException("解析版本快照失败: " + e.getMessage());
        }
    }

    // ==================== 组件 ====================

    public List<VisualDashboardItem> items(String dashboardId) {
        getDashboard(dashboardId);
        return itemMapper.selectList(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getDashboardId, dashboardId)
                .orderByAsc(VisualDashboardItem::getPosY)
                .orderByAsc(VisualDashboardItem::getPosX));
    }

    @Transactional(rollbackFor = Exception.class)
    public void saveItem(VisualDashboardItem item) {
        if (StrUtil.isBlank(item.getDashboardId())) {
            throw new BusinessException("仪表板ID不能为空");
        }
        getDashboard(item.getDashboardId());
        if (StrUtil.isBlank(item.getChartType())) {
            item.setChartType("TABLE");
        }
        if (StrUtil.isBlank(item.getId())) {
            itemMapper.insert(item);
        } else {
            itemMapper.updateById(item);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(String id) {
        itemMapper.deleteById(id);
    }

    // ==================== 分析板 ====================

    /** 分析板列表 */
    public List<VisualBoard> boards(String dashboardId) {
        getDashboard(dashboardId);
        return boardMapper.selectList(new LambdaQueryWrapper<VisualBoard>()
                .eq(VisualBoard::getDashboardId, dashboardId)
                .orderByAsc(VisualBoard::getSortOrder));
    }

    @Transactional(rollbackFor = Exception.class)
    public void createBoard(VisualBoard board) {
        if (StrUtil.isBlank(board.getDashboardId())) {
            throw new BusinessException("仪表板ID不能为空");
        }
        getDashboard(board.getDashboardId());
        if (StrUtil.isBlank(board.getBoardName())) {
            throw new BusinessException("分析板标题不能为空");
        }
        board.setStatus(board.getStatus() == null ? 1 : board.getStatus());
        board.setCollapse(board.getCollapse() == null ? 0 : board.getCollapse());
        board.setLocked(board.getLocked() == null ? 0 : board.getLocked());
        board.setRefreshInterval(board.getRefreshInterval() == null ? 60 : board.getRefreshInterval());
        board.setSortOrder(board.getSortOrder() == null ? 0 : board.getSortOrder());
        boardMapper.insert(board);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateBoard(VisualBoard board) {
        if (StrUtil.isBlank(board.getId())) {
            throw new BusinessException("分析板ID不能为空");
        }
        boardMapper.updateById(board);
    }

    /** 删除分析板（级联删除其组件） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteBoard(String id) {
        itemMapper.delete(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getBoardId, id));
        boardMapper.deleteById(id);
    }

    /** 复制分析板（含其组件），返回新分析板ID */
    @Transactional(rollbackFor = Exception.class)
    public String duplicateBoard(String id) {
        VisualBoard source = boardMapper.selectById(id);
        if (source == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "分析板不存在: " + id);
        }
        VisualBoard copy = new VisualBoard();
        copy.setDashboardId(source.getDashboardId());
        copy.setBoardName(source.getBoardName() + "（副本）");
        copy.setSubtitle(source.getSubtitle());
        copy.setIcon(source.getIcon());
        copy.setBoardType(source.getBoardType());
        copy.setLayout(source.getLayout());
        copy.setRefreshInterval(source.getRefreshInterval());
        copy.setCollapse(0);
        copy.setLocked(source.getLocked());
        copy.setStatus(1);
        copy.setSortOrder(source.getSortOrder());
        boardMapper.insert(copy);

        List<VisualDashboardItem> items = itemMapper.selectList(new LambdaQueryWrapper<VisualDashboardItem>()
                .eq(VisualDashboardItem::getBoardId, id));
        for (VisualDashboardItem item : items) {
            item.setId(null);
            item.setCreateTime(null);
            item.setBoardId(copy.getId());
            itemMapper.insert(item);
        }
        return copy.getId();
    }

    // ==================== 数据执行 ====================

    /** 执行组件查询（可选传入分析板独立筛选 JSON，优先级高于全局筛选） */
    public QueryResult executeItem(String itemId, String filtersJson) {
        VisualDashboardItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "组件不存在: " + itemId);
        }
        if (StrUtil.isBlank(item.getDatasourceId()) || StrUtil.isBlank(item.getQuerySql())) {
            throw new BusinessException("组件未配置数据源或查询SQL");
        }
        String sql = item.getQuerySql();
        if (StrUtil.isNotBlank(filtersJson)) {
            sql = applyFilters(sql, filtersJson);
        }
        return executeOnDataSource(item.getDatasourceId(), sql);
    }

    /**
     * 将分析板独立筛选配置包装进 SQL 的 WHERE 条件。
     * filtersJson 结构：{"timeRange":"30d","dateColumn":"order_date","conditions":[{"field":"category","op":"eq","value":"手机"}]}
     * 实现方式：SELECT * FROM (<原SQL>) t WHERE <条件>，字段名按白名单校验，值统一转义，防注入。
     */
    private String applyFilters(String sql, String filtersJson) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> root = mapper.readValue(filtersJson, new TypeReference<Map<String, Object>>() {});
            List<String> where = new ArrayList<>();

            // 时间范围筛选（需配置日期列）
            String timeRange = root.get("timeRange") == null ? null : String.valueOf(root.get("timeRange"));
            String dateColumn = root.get("dateColumn") == null ? null : String.valueOf(root.get("dateColumn"));
            if (StrUtil.isNotBlank(timeRange) && StrUtil.isNotBlank(dateColumn) && !"all".equals(timeRange)) {
                String start = timeRangeStart(timeRange);
                if (start != null) {
                    where.add(safeColumn(dateColumn) + " >= '" + start + "'");
                }
            }

            // 条件筛选（eq/ne/gt/gte/lt/lte/contains/in）
            Object conds = root.get("conditions");
            if (conds instanceof List) {
                for (Object c : (List<?>) conds) {
                    if (!(c instanceof Map)) continue;
                    Map<?, ?> cond = (Map<?, ?>) c;
                    String field = cond.get("field") == null ? "" : String.valueOf(cond.get("field"));
                    String op = cond.get("op") == null ? "eq" : String.valueOf(cond.get("op"));
                    Object rawVal = cond.get("value");
                    if (StrUtil.isBlank(field) || rawVal == null) continue;
                    String column = safeColumn(field);
                    String value = String.valueOf(rawVal);
                    switch (op) {
                        case "ne":
                            where.add(column + " <> '" + escape(value) + "'");
                            break;
                        case "gt":
                            where.add(column + " > '" + escape(value) + "'");
                            break;
                        case "gte":
                            where.add(column + " >= '" + escape(value) + "'");
                            break;
                        case "lt":
                            where.add(column + " < '" + escape(value) + "'");
                            break;
                        case "lte":
                            where.add(column + " <= '" + escape(value) + "'");
                            break;
                        case "contains":
                            where.add(column + " LIKE '%" + escape(value) + "%'");
                            break;
                        case "in":
                            String[] items = value.split(",");
                            List<String> esc = new ArrayList<>();
                            for (String it : items) esc.add("'" + escape(it.trim()) + "'");
                            where.add(column + " IN (" + String.join(",", esc) + ")");
                            break;
                        default: // eq
                            where.add(column + " = '" + escape(value) + "'");
                    }
                }
            }

            if (where.isEmpty()) return sql;
            return "SELECT * FROM (" + sql + ") t WHERE " + String.join(" AND ", where);
        } catch (Exception e) {
            log.warn("应用分析板筛选失败，忽略筛选: {}", e.getMessage());
            return sql;
        }
    }

    /** 字段名白名单校验：仅允许字母数字下划线，防止注入 */
    private String safeColumn(String col) {
        return col.replaceAll("[^a-zA-Z0-9_\\u4e00-\\u9fa5]", "");
    }

    /** 单引号转义 */
    private String escape(String s) {
        return s == null ? "" : s.replace("'", "''");
    }

    /** 时间范围 → 起始日期字符串 yyyy-MM-dd */
    private String timeRangeStart(String timeRange) {
        LocalDate today = LocalDate.now();
        switch (timeRange) {
            case "today":
                return today.toString();
            case "yesterday":
                return today.minusDays(1).toString();
            case "7d":
                return today.minusDays(6).toString();
            case "30d":
                return today.minusDays(29).toString();
            case "month":
                return today.withDayOfMonth(1).toString();
            case "lastMonth":
                return today.minusMonths(1).withDayOfMonth(1).toString();
            case "year":
                return today.withDayOfMonth(1).withMonth(1).toString();
            default:
                return null;
        }
    }

    /** 即席分析查询（分析板）—— 含 SQL 审核 / 表权限 / 参数解析 / 行数上限 / 执行历史 */
    public AdhocExecuteResponse executeAdhoc(AdhocQueryRequest request) {
        if (StrUtil.isBlank(request.getDatasourceId())) {
            throw new BusinessException("数据源ID不能为空");
        }
        if (StrUtil.isBlank(request.getSql())) {
            throw new BusinessException("SQL 不能为空");
        }
        String userId = MetadataHolder.getUserId();

        // 1. SQL 审核（拦截 DDL / 危险操作 / 多语句注入）
        String sql = SqlAuditUtil.audit(request.getSql());
        // 2. 参数解析 ${param}
        sql = resolveParams(sql, request.getParams());

        // 3. 行/列权限 SQL 改写
        sql = applyPermissionRewrite(sql, userId);

        // 4. 表权限校验（超级管理员跳过）
        if (adhocPermissionCheck && !MetadataHolder.isSuperAdmin() && StrUtil.isNotBlank(userId)) {
            checkTablePermission(request.getDatasourceId(), sql, userId);
        }

        // 5. 执行 + 记录历史
        long start = System.currentTimeMillis();
        try {
            R<QueryResult> result = datasourceApiClient.executeRaw(request.getDatasourceId(), sql);
            long cost = System.currentTimeMillis() - start;
            if (result == null || result.getCode() != 0) {
                String error = result == null ? "查询失败" : result.getMsg();
                saveAdhocHistory(request, userId, sql, 0, cost, 0, error);
                throw new BusinessException(error);
            }
            QueryResult data = result.getData();
            if (data == null) data = new QueryResult();

            // 行数上限截断
            List<Map<String, Object>> rows = data.getRows();
            int original = rows == null ? 0 : rows.size();
            boolean truncated = false;
            if (rows != null && rows.size() > adhocMaxRows) {
                data.setRows(new ArrayList<>(rows.subList(0, adhocMaxRows)));
                truncated = true;
            }
            data.setRowCount(data.getRows() == null ? 0 : data.getRows().size());

            saveAdhocHistory(request, userId, sql, 1, cost, data.getRowCount(), null);
            AdhocExecuteResponse resp = new AdhocExecuteResponse();
            resp.setResult(data);
            resp.setTruncated(truncated);
            resp.setOriginalRowCount(truncated ? original : data.getRowCount());
            return resp;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            saveAdhocHistory(request, userId, sql, 0, cost, 0, e.getMessage());
            throw new BusinessException("即席查询执行失败: " + e.getMessage());
        }
    }

    /** 解析 SQL 中的 ${param} 占位符：数字原样替换，其余加单引号转义；缺失参数直接报错 */
    private String resolveParams(String sql, Map<String, Object> params) {
        Pattern pattern = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");
        Matcher matcher = pattern.matcher(sql);
        if (!matcher.find()) {
            // SQL 无占位符，直接返回
            return sql;
        }
        // SQL 含占位符：无论是否传参，都需校验并提供参数值，缺失即报错
        if (params == null) {
            params = Collections.emptyMap();
        }
        matcher.reset();
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!params.containsKey(key)) {
                throw new BusinessException("SQL 参数缺失: " + key);
            }
            Object val = params.get(key);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(renderParamValue(val)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String renderParamValue(Object val) {
        if (val == null) {
            return "NULL";
        }
        String s = String.valueOf(val);
        if (s.matches("-?\\d+(\\.\\d+)?")) {
            return s; // 数字原样
        }
        return "'" + s.replace("'", "''") + "'"; // 字符串加引号
    }

    /** 提取 FROM 表并校验用户表权限 */
    private void checkTablePermission(String datasourceId, String sql, String userId) {
        Matcher matcher = FROM_PATTERN.matcher(sql);
        if (!matcher.find()) {
            return;
        }
        String table = matcher.group(1);
        String database = null;
        int dot = table.indexOf('.');
        if (dot > 0) {
            database = table.substring(0, dot);
            table = table.substring(dot + 1);
        }
        boolean allowed = permissionApiClient.checkTablePermission(userId, datasourceId, database, table, "SELECT");
        if (!allowed) {
            throw new BusinessException("没有对表 " + (database == null ? "" : database + ".") + table + " 的查询权限");
        }
    }

    /** 简单提取 FROM 表名（含可选 schema 前缀） */
    private static final Pattern FROM_PATTERN = Pattern.compile(
            "\\bFROM\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*)",
            Pattern.CASE_INSENSITIVE);

    /** 提取 FROM/JOIN 后的所有表名 */
    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "\\b(?:FROM|JOIN)\\s+([a-zA-Z_][\\w$]*(?:\\.[a-zA-Z_][\\w$]*)*)",
            Pattern.CASE_INSENSITIVE);

    /**
     * 权限 SQL 改写：提取 SQL 中所有表名，逐表查询行/列策略并改写。
     * 超级管理员、无策略或未登录时直接返回原 SQL。
     */
    private String applyPermissionRewrite(String sql, String userId) {
        if (StrUtil.isBlank(sql) || StrUtil.isBlank(userId) || MetadataHolder.isSuperAdmin()) {
            return sql;
        }
        try {
            Set<String> tables = extractTableNames(sql);
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
                log.info("[visual] SQL 改写生效 tables={} rows={} cols={}",
                        tables, result.getAppliedRows().size(), result.getAppliedColumns().size());
                return result.getSql();
            }
        } catch (Exception e) {
            log.warn("[visual] SQL 权限改写异常，使用原始 SQL: {}", e.getMessage());
        }
        return sql;
    }

    /** 从 SQL 中提取 FROM/JOIN 涉及的表名（剥离 schema 前缀） */
    private Set<String> extractTableNames(String sql) {
        Set<String> tables = new java.util.LinkedHashSet<>();
        Matcher matcher = TABLE_PATTERN.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1);
            if (StrUtil.isNotBlank(table)) {
                int dot = table.lastIndexOf('.');
                tables.add(dot >= 0 ? table.substring(dot + 1) : table);
            }
        }
        return tables;
    }

    private void saveAdhocHistory(AdhocQueryRequest request, String userId, String sql,
                                   int status, long costMs, int rowCount, String error) {
        try {
            VisualAdhocHistory history = new VisualAdhocHistory();
            history.setAdhocId(request.getAdhocId());
            history.setUserId(userId);
            history.setDatasourceId(request.getDatasourceId());
            history.setSqlText(sql);
            history.setStatus(status);
            history.setCostMs(costMs);
            history.setRowCount(rowCount);
            history.setErrorMessage(error);
            adhocHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("记录即席查询历史失败: {}", e.getMessage());
        }
    }

    /**
     * 组件下钻查询。
     * 优先使用组件配置的 drillSql（明细/次级聚合口径），否则回退到组件原查询SQL；
     * 将点击的维度列=值作为 WHERE 条件注入，再叠加分析板独立筛选。
     */
    public QueryResult drillItem(String itemId, String column, String value, String filtersJson) {
        VisualDashboardItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "组件不存在: " + itemId);
        }
        if (StrUtil.isBlank(item.getDatasourceId()) || (StrUtil.isBlank(item.getQuerySql()) && StrUtil.isBlank(item.getDrillSql()))) {
            throw new BusinessException("组件未配置数据源或查询SQL");
        }
        String sql = StrUtil.isNotBlank(item.getDrillSql()) ? item.getDrillSql() : item.getQuerySql();
        sql = StrUtil.isBlank(sql) ? item.getQuerySql() : sql;
        List<String> where = new ArrayList<>();
        if (StrUtil.isNotBlank(column) && value != null) {
            where.add(safeColumn(column) + " = '" + escape(value) + "'");
        }
        if (StrUtil.isNotBlank(filtersJson)) {
            String wrapped = applyFilters(sql, filtersJson);
            // applyFilters 已注入条件，直接复用
            sql = wrapped;
        }
        if (!where.isEmpty()) {
            sql = "SELECT * FROM (" + sql + ") t WHERE " + String.join(" AND ", where);
        }
        return executeOnDataSource(item.getDatasourceId(), sql);
    }

    private QueryResult executeOnDataSource(String datasourceId, String sql) {
        R<QueryResult> result = datasourceApiClient.executeRaw(datasourceId, sql);
        if (result == null || result.getCode() != 0) {
            throw new BusinessException(result == null ? "查询失败" : result.getMsg());
        }
        return result.getData();
    }

    // ==================== 即席查询收藏 ====================

    /** 保存（新增/更新）即席查询 */
    @Transactional(rollbackFor = Exception.class)
    public void saveAdhoc(AdhocSaveRequest request) {
        if (StrUtil.isBlank(request.getName())) {
            throw new BusinessException("查询名称不能为空");
        }
        if (StrUtil.isBlank(request.getDatasourceId())) {
            throw new BusinessException("数据源ID不能为空");
        }
        if (StrUtil.isBlank(request.getSql())) {
            throw new BusinessException("SQL 不能为空");
        }
        VisualAdhocQuery entity = new VisualAdhocQuery();
        entity.setName(request.getName());
        entity.setDatasourceId(request.getDatasourceId());
        entity.setSqlText(request.getSql());
        entity.setFolder(request.getFolder());
        try {
            entity.setParamsJson(request.getParams() == null ? null : objectMapper.writeValueAsString(request.getParams()));
        } catch (Exception e) {
            throw new BusinessException("参数序列化失败: " + e.getMessage());
        }
        if (StrUtil.isBlank(request.getId())) {
            entity.setCreateBy(MetadataHolder.getUserId());
            adhocQueryMapper.insert(entity);
        } else {
            VisualAdhocQuery existing = adhocQueryMapper.selectById(request.getId());
            if (existing == null) {
                throw new BusinessException(ResultCode.NOT_FOUND, "收藏查询不存在: " + request.getId());
            }
            entity.setId(request.getId());
            adhocQueryMapper.updateById(entity);
        }
    }

    /** 收藏查询分页（按当前用户隔离） */
    public PageResult<VisualAdhocQuery> adhocQueryPage(long current, long size, String keyword, String userId) {
        ensureSeedTemplates(userId);
        Page<VisualAdhocQuery> page = adhocQueryMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VisualAdhocQuery>()
                        .eq(StrUtil.isNotBlank(userId), VisualAdhocQuery::getCreateBy, userId)
                        .like(StrUtil.isNotBlank(keyword), VisualAdhocQuery::getName, keyword)
                        .orderByDesc(VisualAdhocQuery::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /**
     * 首次访问时为用户预置内置模板（种子模板），之后完全归属该用户、可增删改。
     * 仅当该用户当前没有任何模板记录时才注入，避免重复。
     */
    @Transactional(rollbackFor = Exception.class)
    public void ensureSeedTemplates(String userId) {
        if (StrUtil.isBlank(userId)) {
            return;
        }
        Long count = adhocQueryMapper.selectCount(
                new LambdaQueryWrapper<VisualAdhocQuery>().eq(VisualAdhocQuery::getCreateBy, userId));
        if (count != null && count > 0) {
            return;
        }
        for (SeedTemplate t : seedTemplates) {
            VisualAdhocQuery entity = new VisualAdhocQuery();
            entity.setName(t.name);
            entity.setDatasourceId(t.datasourceId);
            entity.setSqlText(t.sql);
            entity.setFolder("内置模板");
            entity.setCreateBy(userId);
            adhocQueryMapper.insert(entity);
        }
    }

    /** 内置种子模板（演示用，用户首次访问自动注入，可修改/删除） */
    private static final class SeedTemplate {
        final String name;
        final String datasourceId;
        final String sql;
        SeedTemplate(String name, String datasourceId, String sql) {
            this.name = name;
            this.datasourceId = datasourceId;
            this.sql = sql;
        }
    }

    private static final List<SeedTemplate> seedTemplates = Arrays.asList(
            new SeedTemplate("各省销售额 TOP10", null,
                    "SELECT province, SUM(amount) AS 销售额, SUM(qty) AS 销量, ROUND(SUM(profit)/SUM(amount)*100,2) AS 利润率\n" +
                    "FROM demo_fact_order\nGROUP BY province\nORDER BY 销售额 DESC\nLIMIT 10"),
            new SeedTemplate("按月销售趋势", null,
                    "SELECT DATE_FORMAT(order_date,'%Y-%m') AS 月份, SUM(amount) AS 销售额, SUM(profit) AS 利润\n" +
                    "FROM demo_fact_order\nGROUP BY DATE_FORMAT(order_date,'%Y-%m')\nORDER BY 月份"),
            new SeedTemplate("渠道×类目销售矩阵", null,
                    "SELECT c.channel AS 渠道, ca.category AS 类目, SUM(o.amount) AS 销售额\n" +
                    "FROM demo_fact_order o\nJOIN demo_dim_channel c ON o.channel_id = c.id\n" +
                    "JOIN demo_dim_category ca ON o.category_id = ca.id\nGROUP BY c.channel, ca.category\nORDER BY 渠道, 销售额 DESC"),
            new SeedTemplate("大区销售额占比", null,
                    "SELECT r.region AS 大区, SUM(o.amount) AS 销售额, COUNT(*) AS 订单数\n" +
                    "FROM demo_fact_order o\nJOIN demo_dim_region r ON o.region_id = r.id\nGROUP BY r.region\nORDER BY 销售额 DESC"),
            new SeedTemplate("高利润类目 TOP8", null,
                    "SELECT ca.category AS 类目, ROUND(AVG(o.profit),2) AS 平均利润, ROUND(AVG(o.profit)/AVG(o.amount)*100,2) AS 平均利润率\n" +
                    "FROM demo_fact_order o\nJOIN demo_dim_category ca ON o.category_id = ca.id\n" +
                    "GROUP BY ca.category\nORDER BY 平均利润 DESC\nLIMIT 8"),
            new SeedTemplate("近30天逐日订单(城市)", null,
                    "SELECT order_date AS 日期, city AS 城市, channel AS 渠道, category AS 类目, amount AS 金额, qty AS 数量, profit AS 利润\n" +
                    "FROM demo_daily_order\nORDER BY order_date DESC\nLIMIT 100"),
            new SeedTemplate("参数示例：指定月份销售额", null,
                    "SELECT province AS 省份, SUM(amount) AS 销售额\n" +
                    "FROM demo_fact_order\nWHERE DATE_FORMAT(order_date,'%Y-%m') = '${month}'\nGROUP BY province\nORDER BY 销售额 DESC"),
            new SeedTemplate("订单明细(含维度)", null,
                    "SELECT o.order_date AS 订单日期, o.province AS 省份, c.channel AS 渠道, ca.category AS 类目,\n" +
                    "       o.amount AS 金额, o.qty AS 数量, o.profit AS 利润\n" +
                    "FROM demo_fact_order o\nJOIN demo_dim_channel c ON o.channel_id = c.id\n" +
                    "JOIN demo_dim_category ca ON o.category_id = ca.id\nORDER BY o.order_date DESC\nLIMIT 100"));

    public VisualAdhocQuery getAdhocQuery(String id) {
        VisualAdhocQuery query = adhocQueryMapper.selectById(id);
        if (query == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "收藏查询不存在: " + id);
        }
        return query;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAdhocQuery(String id) {
        if (adhocQueryMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "收藏查询不存在: " + id);
        }
        adhocQueryMapper.deleteById(id);
    }

    /** 收藏查询执行历史（分页） */
    public PageResult<VisualAdhocHistory> adhocHistoryPage(long current, long size, String userId) {
        Page<VisualAdhocHistory> page = adhocHistoryMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VisualAdhocHistory>()
                        .eq(StrUtil.isNotBlank(userId), VisualAdhocHistory::getUserId, userId)
                        .orderByDesc(VisualAdhocHistory::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 将即席查询存为仪表板组件，返回组件ID */
    @Transactional(rollbackFor = Exception.class)
    public String saveAdhocAsItem(SaveAsItemRequest request) {
        if (StrUtil.isBlank(request.getDashboardId())) {
            throw new BusinessException("目标仪表板ID不能为空");
        }
        getDashboard(request.getDashboardId());
        if (StrUtil.isBlank(request.getTitle())) {
            throw new BusinessException("组件标题不能为空");
        }
        VisualDashboardItem item = new VisualDashboardItem();
        item.setDashboardId(request.getDashboardId());
        item.setTitle(request.getTitle());
        item.setChartType(StrUtil.isBlank(request.getChartType()) ? "TABLE" : request.getChartType());
        item.setDatasourceId(request.getDatasourceId());
        item.setQuerySql(request.getSql());
        item.setConfig(request.getConfig());
        item.setPosX(0);
        item.setPosY(0);
        item.setWidth(6);
        item.setHeight(4);
        itemMapper.insert(item);
        return item.getId();
    }

    /** 查询结果转 CSV（含 UTF-8 BOM，避免 Excel 中文乱码） */
    public String toCsv(QueryResult result) {
        StringBuilder sb = new StringBuilder("﻿");
        if (result.getColumns() != null) {
            sb.append(result.getColumns().stream()
                    .map(ColumnInfo::getColumnName)
                    .map(this::csvEscape)
                    .collect(java.util.stream.Collectors.joining(",")));
            sb.append("\r\n");
        }
        if (result.getRows() != null) {
            for (Map<String, Object> row : result.getRows()) {
                sb.append(result.getColumns().stream()
                        .map(c -> String.valueOf(row.get(c.getColumnName())))
                        .map(this::csvEscape)
                        .collect(java.util.stream.Collectors.joining(",")));
                sb.append("\r\n");
            }
        }
        return sb.toString();
    }

    private String csvEscape(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
