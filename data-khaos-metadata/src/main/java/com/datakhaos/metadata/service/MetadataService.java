package com.datakhaos.metadata.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.datasource.api.connector.DatasourceApiClient;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.metadata.entity.MetaColumn;
import com.datakhaos.metadata.entity.MetaDatabase;
import com.datakhaos.metadata.entity.MetaDictType;
import com.datakhaos.metadata.entity.MetaStandard;
import com.datakhaos.metadata.entity.MetaTable;
import com.datakhaos.metadata.entity.MetaTableLineage;
import com.datakhaos.metadata.mapper.MetaColumnMapper;
import com.datakhaos.metadata.mapper.MetaDatabaseMapper;
import com.datakhaos.metadata.mapper.MetaDictTypeMapper;
import com.datakhaos.metadata.mapper.MetaStandardMapper;
import com.datakhaos.metadata.mapper.MetaTableLineageMapper;
import com.datakhaos.metadata.mapper.MetaTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据服务：通过 DatasourceApiClient 拉取库/表/字段并落库（幂等 upsert），
 * 提供结构树、检索与血缘能力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataService {

    private final MetaDatabaseMapper databaseMapper;
    private final MetaTableMapper tableMapper;
    private final MetaColumnMapper columnMapper;
    private final MetaTableLineageMapper lineageMapper;
    private final MetaDictTypeMapper dictTypeMapper;
    private final MetaStandardMapper standardMapper;
    private final DatasourceApiClient datasourceApiClient;

    // ---------- 采集同步 ----------

    /** 全量同步：数据源下所有库 -> 表 -> 字段 */
    @Transactional(rollbackFor = Exception.class)
    public void sync(String datasourceId) {
        List<String> databases = datasourceApiClient.databases(datasourceId);
        if (databases.isEmpty()) {
            log.warn("数据源 {} 未返回任何数据库", datasourceId);
            return;
        }
        for (String database : databases) {
            syncDatabase(datasourceId, database);
        }
        log.info("数据源 {} 元数据同步完成，共 {} 个库", datasourceId, databases.size());
    }

    /** 同步单个库下的表与字段 */
    @Transactional(rollbackFor = Exception.class)
    public void syncDatabase(String datasourceId, String database) {
        MetaDatabase db = upsertDatabase(datasourceId, database);
        List<String> tables = datasourceApiClient.tables(datasourceId, database);
        for (String tableName : tables) {
            MetaTable table = upsertTable(db.getId(), tableName, null);
            List<ColumnInfo> columns = datasourceApiClient.columnInfos(datasourceId, database, tableName);
            for (ColumnInfo column : columns) {
                upsertColumn(table.getId(), column);
            }
        }
    }

    /** 同步单表字段 */
    public void syncTable(String datasourceId, String database, String tableName) {
        MetaDatabase db = upsertDatabase(datasourceId, database);
        MetaTable table = upsertTable(db.getId(), tableName, null);
        List<ColumnInfo> columns = datasourceApiClient.columnInfos(datasourceId, database, tableName);
        for (ColumnInfo column : columns) {
            upsertColumn(table.getId(), column);
        }
    }

    // ---------- 查询 ----------

    /** 结构树：库 -> 表 -> 字段 */
    public List<Map<String, Object>> structure(String datasourceId) {
        List<MetaDatabase> databases = databaseMapper.selectList(new LambdaQueryWrapper<MetaDatabase>()
                .eq(MetaDatabase::getDatasourceId, datasourceId)
                .orderByAsc(MetaDatabase::getDatabaseName));
        List<Map<String, Object>> result = new ArrayList<>();
        for (MetaDatabase db : databases) {
            List<MetaTable> tables = tableMapper.selectList(new LambdaQueryWrapper<MetaTable>()
                    .eq(MetaTable::getDatabaseId, db.getId())
                    .orderByAsc(MetaTable::getTableName));
            List<Map<String, Object>> tableNodes = new ArrayList<>();
            for (MetaTable table : tables) {
                List<MetaColumn> columns = columnMapper.selectList(new LambdaQueryWrapper<MetaColumn>()
                        .eq(MetaColumn::getTableId, table.getId())
                        .orderByAsc(MetaColumn::getSortOrder));
                Map<String, Object> tableNode = new LinkedHashMap<>();
                tableNode.put("table", table);
                tableNode.put("columns", columns);
                tableNodes.add(tableNode);
            }
            Map<String, Object> dbNode = new LinkedHashMap<>();
            dbNode.put("database", db);
            dbNode.put("tables", tableNodes);
            result.add(dbNode);
        }
        return result;
    }

    /** 数据库列表（已采集） */
    public List<MetaDatabase> databases(String datasourceId) {
        return databaseMapper.selectList(new LambdaQueryWrapper<MetaDatabase>()
                .eq(MetaDatabase::getDatasourceId, datasourceId)
                .orderByAsc(MetaDatabase::getDatabaseName));
    }

    /** 分页查询表 */
    public PageResult<MetaTable> tablePage(long current, long size, String datasourceId, String keyword) {
        Page<MetaTable> page = tableMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MetaTable>()
                        .like(StrUtil.isNotBlank(keyword), MetaTable::getTableName, keyword)
                        .orderByAsc(MetaTable::getTableName));
        if (StrUtil.isNotBlank(datasourceId)) {
            // 按数据源过滤：先找其库，再过滤表
            List<String> dbIds = databaseMapper.selectList(new LambdaQueryWrapper<MetaDatabase>()
                            .eq(MetaDatabase::getDatasourceId, datasourceId))
                    .stream().map(MetaDatabase::getId).toList();
            page = dbIds.isEmpty() ? new Page<>(current, size) : tableMapper.selectPage(new Page<>(current, size),
                    new LambdaQueryWrapper<MetaTable>()
                            .in(MetaTable::getDatabaseId, dbIds)
                            .like(StrUtil.isNotBlank(keyword), MetaTable::getTableName, keyword)
                            .orderByAsc(MetaTable::getTableName));
        }
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 分页查询字段 */
    public PageResult<MetaColumn> columnPage(long current, long size, String tableId) {
        Page<MetaColumn> page = columnMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MetaColumn>()
                        .eq(StrUtil.isNotBlank(tableId), MetaColumn::getTableId, tableId)
                        .orderByAsc(MetaColumn::getSortOrder));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    // ---------- 字段治理 ----------

    /** 更新字段业务元数据（业务名/业务说明/字典关联），采集时不会被覆盖 */
    public void updateColumn(String id, MetaColumn patch) {
        MetaColumn exist = columnMapper.selectById(id);
        if (exist == null) {
            throw new BusinessException("字段不存在");
        }
        exist.setBizName(patch.getBizName());
        exist.setBizComment(patch.getBizComment());
        exist.setDescription(patch.getDescription());
        exist.setSensitiveLevel(patch.getSensitiveLevel());
        // 字典关联：填充字典类型名称
        String dictCode = patch.getDictTypeCode();
        if (StrUtil.isBlank(dictCode)) {
            exist.setDictTypeCode(null);
            exist.setDictTypeName(null);
        } else {
            MetaDictType dictType = dictTypeMapper.selectOne(new LambdaQueryWrapper<MetaDictType>()
                    .eq(MetaDictType::getTypeCode, dictCode).last("limit 1"));
            if (dictType == null) {
                throw new BusinessException("字典类型不存在: " + dictCode);
            }
            exist.setDictTypeCode(dictType.getTypeCode());
            exist.setDictTypeName(dictType.getTypeName());
        }
        columnMapper.updateById(exist);
    }

    /** 数据标准落标校验：比对字段与标准的数据类型/长度/枚举 */
    public Map<String, Object> checkColumnStandard(String columnId, String stdCode) {
        MetaColumn column = columnMapper.selectById(columnId);
        if (column == null) {
            throw new BusinessException("字段不存在");
        }
        MetaStandard standard = standardMapper.selectOne(new LambdaQueryWrapper<MetaStandard>()
                .eq(MetaStandard::getStdCode, stdCode).last("limit 1"));
        if (standard == null) {
            throw new BusinessException("数据标准不存在: " + stdCode);
        }
        String colType = StrUtil.nullToDefault(column.getColumnType(), "").toUpperCase();
        String stdType = StrUtil.nullToDefault(standard.getDataType(), "").toUpperCase();
        boolean typeMatch = StrUtil.isBlank(stdType) || colType.startsWith(stdType);
        boolean lengthMatch = standard.getDataLength() == null
                || (column.getColumnLength() != null && column.getColumnLength() <= standard.getDataLength());
        boolean enumMatch = true;
        String enumHint = null;
        if (StrUtil.isNotBlank(standard.getEnumRange())) {
            // 字段已关联字典即可认为满足枚举约束
            enumMatch = StrUtil.isNotBlank(column.getDictTypeCode());
            if (!enumMatch) {
                enumHint = "字段未关联字典，但标准要求枚举：" + standard.getEnumRange();
            }
        }
        boolean matched = typeMatch && lengthMatch && enumMatch;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columnId", columnId);
        result.put("columnName", column.getColumnName());
        result.put("stdCode", standard.getStdCode());
        result.put("stdName", standard.getStdName());
        result.put("matched", matched);
        result.put("typeMatch", typeMatch);
        result.put("lengthMatch", lengthMatch);
        result.put("enumMatch", enumMatch);
        result.put("enumHint", enumHint);
        return result;
    }

    /** 检索：表名 / 表注释 / 字段名 */
    public List<Map<String, Object>> search(String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (StrUtil.isBlank(keyword)) {
            return result;
        }
        List<MetaTable> tables = tableMapper.selectList(new LambdaQueryWrapper<MetaTable>()
                .like(MetaTable::getTableName, keyword)
                .or().like(MetaTable::getDescription, keyword));
        for (MetaTable table : tables) {
            MetaDatabase db = table.getDatabaseId() == null ? null : databaseMapper.selectById(table.getDatabaseId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "TABLE");
            item.put("table", table);
            item.put("databaseName", db == null ? null : db.getDatabaseName());
            result.add(item);
        }
        List<MetaColumn> columns = columnMapper.selectList(new LambdaQueryWrapper<MetaColumn>()
                .like(MetaColumn::getColumnName, keyword)
                .or().like(MetaColumn::getDescription, keyword)
                .or().like(MetaColumn::getBizName, keyword)
                .or().like(MetaColumn::getBizComment, keyword)
                .or().like(MetaColumn::getDictTypeName, keyword));
        for (MetaColumn column : columns) {
            MetaTable table = column.getTableId() == null ? null : tableMapper.selectById(column.getTableId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "COLUMN");
            item.put("column", column);
            item.put("tableName", table == null ? null : table.getTableName());
            result.add(item);
        }
        return result;
    }

    // ---------- 血缘 ----------

    /** 查询某表的血缘（上下游） */
    public List<MetaTableLineage> lineage(String tableId) {
        return lineageMapper.selectList(new LambdaQueryWrapper<MetaTableLineage>()
                .eq(MetaTableLineage::getSourceTableId, tableId)
                .or().eq(MetaTableLineage::getTargetTableId, tableId));
    }

    /** 记录血缘关系 */
    public void saveLineage(MetaTableLineage lineage) {
        if (StrUtil.isBlank(lineage.getSourceTableId()) || StrUtil.isBlank(lineage.getTargetTableId())) {
            throw new BusinessException("源表与目标表不能为空");
        }
        if (lineage.getRelationType() == null) {
            lineage.setRelationType("ETL");
        }
        lineageMapper.insert(lineage);
    }

    /**
     * SQL 血缘自动分析：解析 INSERT INTO/CREATE TABLE AS ... SELECT ... FROM/JOIN 语句，
     * 提取目标表与所有源表，自动写入表级血缘（relation_type = SQL）。
     * 采用轻量正则解析（表级血缘足够），避免引入 SQL 解析器与 MyBatis-Plus 分页插件的 jsqlparser 版本冲突。
     *
     * @return 新写入的血缘关系（含已解析出的源-目标表ID对）
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MetaTableLineage> analyzeSqlLineage(String datasourceId, String database, String sql) {
        List<MetaTableLineage> created = new ArrayList<>();
        if (StrUtil.isBlank(sql)) {
            return created;
        }
        String normalized = sql.replaceAll("(?i)\\b(\\/\\*[\\s\\S]*?\\*\\/|--[^\\n]*|#[^\\n]*)", " ")
                .replaceAll("\\s+", " ").trim();
        String target = extractTargetTable(normalized);
        if (StrUtil.isBlank(target)) {
            return created;
        }
        String targetId = tableIdOf(datasourceId, database, target);
        if (targetId == null) {
            log.warn("血缘解析：目标表 {} 未同步元数据，已跳过", target);
            return created;
        }
        for (String source : extractSourceTables(normalized)) {
            if (source.equalsIgnoreCase(target)) {
                continue;
            }
            String sourceId = tableIdOf(datasourceId, database, source);
            if (sourceId == null) {
                continue;
            }
            boolean notExists = lineageMapper.selectCount(new LambdaQueryWrapper<MetaTableLineage>()
                    .eq(MetaTableLineage::getSourceTableId, sourceId)
                    .eq(MetaTableLineage::getTargetTableId, targetId)) == 0;
            if (notExists) {
                MetaTableLineage lineage = new MetaTableLineage();
                lineage.setSourceTableId(sourceId);
                lineage.setTargetTableId(targetId);
                lineage.setRelationType("SQL");
                lineageMapper.insert(lineage);
                created.add(lineage);
            }
        }
        return created;
    }

    /** 提取 INSERT INTO / CREATE TABLE 的目标表名 */
    private String extractTargetTable(String sql) {
        java.util.regex.Matcher insert = java.util.regex.Pattern.compile(
                "\\binsert\\s+into\\s+([\\w$#.\"'`\\[\\]]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        if (insert.find()) {
            return stripQualifier(insert.group(1).replaceAll("[\"'`\\[\\]]", ""));
        }
        java.util.regex.Matcher ct = java.util.regex.Pattern.compile(
                "\\bcreate\\s+(?:temporary\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([\\w$#.\"'`\\[\\]]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        if (ct.find()) {
            return stripQualifier(ct.group(1).replaceAll("[\"'`\\[\\]]", ""));
        }
        return null;
    }

    /**
     * 提取 FROM / JOIN 子句后的源表名（跳过括号内子查询）。
     * 仅命中不在任何括号内的 from/join，取其后的表名 token。
     */
    private List<String> extractSourceTables(String sql) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher kw = java.util.regex.Pattern.compile(
                "\\b(from|join)\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(sql);
        int searchStart = 0;
        while (kw.find(searchStart)) {
            int kwStart = kw.start();
            if (countDepth(sql, kwStart) > 0) {
                searchStart = kw.end();
                continue;
            }
            java.util.regex.Matcher t = java.util.regex.Pattern.compile(
                    "^[ \\t\\n]*([\\w$#.\"'`\\[\\]]+)").matcher(sql.substring(kw.end()));
            if (t.find()) {
                String name = t.group(1).replaceAll("[\"'`\\[\\]]", "");
                if (!name.equalsIgnoreCase("select") && !name.equalsIgnoreCase("from")
                        && !name.equalsIgnoreCase("join") && !name.equalsIgnoreCase("with")
                        && !name.equalsIgnoreCase("where") && !name.equalsIgnoreCase("(")) {
                    out.add(name);
                }
            }
            searchStart = kw.end();
        }
        return out;
    }

    /** 计算某位置前未闭合的括号深度 */
    private int countDepth(String sql, int upto) {
        int depth = 0;
        for (int i = 0; i < upto; i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }
        return depth;
    }

    /** 去除 schema 前缀（仅保留表名用于元数据匹配） */
    private String stripQualifier(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    /** 按数据源/库/表名解析表ID */
    public String tableIdOf(String datasourceId, String database, String tableName) {
        MetaDatabase db = databaseMapper.selectOne(new LambdaQueryWrapper<MetaDatabase>()
                .eq(MetaDatabase::getDatasourceId, datasourceId)
                .eq(MetaDatabase::getDatabaseName, database));
        if (db == null) {
            return null;
        }
        MetaTable table = tableMapper.selectOne(new LambdaQueryWrapper<MetaTable>()
                .eq(MetaTable::getDatabaseId, db.getId())
                .eq(MetaTable::getTableName, tableName));
        return table == null ? null : table.getId();
    }

    // ---------- upsert ----------

    private MetaDatabase upsertDatabase(String datasourceId, String databaseName) {
        MetaDatabase exist = databaseMapper.selectOne(new LambdaQueryWrapper<MetaDatabase>()
                .eq(MetaDatabase::getDatasourceId, datasourceId)
                .eq(MetaDatabase::getDatabaseName, databaseName));
        MetaDatabase db = exist == null ? new MetaDatabase() : exist;
        db.setDatasourceId(datasourceId);
        db.setDatabaseName(databaseName);
        db.setSyncTime(LocalDateTime.now());
        if (exist == null) {
            databaseMapper.insert(db);
        } else {
            databaseMapper.updateById(db);
        }
        return db;
    }

    private MetaTable upsertTable(String databaseId, String tableName, String tableType) {
        MetaTable exist = tableMapper.selectOne(new LambdaQueryWrapper<MetaTable>()
                .eq(MetaTable::getDatabaseId, databaseId)
                .eq(MetaTable::getTableName, tableName));
        MetaTable table = exist == null ? new MetaTable() : exist;
        table.setDatabaseId(databaseId);
        table.setTableName(tableName);
        if (StrUtil.isNotBlank(tableType)) {
            table.setTableType(tableType);
        }
        table.setSyncTime(LocalDateTime.now());
        if (exist == null) {
            tableMapper.insert(table);
        } else {
            tableMapper.updateById(table);
        }
        return table;
    }

    private MetaColumn upsertColumn(String tableId, ColumnInfo info) {
        MetaColumn exist = columnMapper.selectOne(new LambdaQueryWrapper<MetaColumn>()
                .eq(MetaColumn::getTableId, tableId)
                .eq(MetaColumn::getColumnName, info.getColumnName()));
        MetaColumn column = exist == null ? new MetaColumn() : exist;
        column.setTableId(tableId);
        column.setColumnName(info.getColumnName());
        column.setColumnType(info.getColumnType());
        column.setColumnLength(info.getColumnLength());
        column.setColumnScale(info.getColumnScale());
        column.setIsNullable(Boolean.TRUE.equals(info.getNullable()) ? 1 : 0);
        column.setIsPrimaryKey(Boolean.TRUE.equals(info.getPrimaryKey()) ? 1 : 0);
        column.setDefaultValue(info.getDefaultValue());
        // description：物理注释仅在未治理（未自定义展示描述）时回填，保护已治理的展示描述
        if (StrUtil.isBlank(column.getDescription())) {
            column.setDescription(info.getDescription());
        }
        column.setSortOrder(info.getSortOrder());
        // sensitiveLevel：仅在未治理（当前为普通）时回填物理值，保护已治理的敏感级
        if (column.getSensitiveLevel() == null || column.getSensitiveLevel() == 0) {
            column.setSensitiveLevel(info.getSensitiveLevel());
        }
        if (exist == null) {
            columnMapper.insert(column);
        } else {
            columnMapper.updateById(column);
        }
        return column;
    }
}
