package com.datakhaos.datasource.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.util.EncryptUtil;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.DsConfig;
import com.datakhaos.datasource.api.model.QueryResult;
import com.datakhaos.datasource.config.DatasourceProperties;
import com.datakhaos.datasource.connector.DataSourceConnectorFactory;
import com.datakhaos.datasource.entity.MetaDatasource;
import com.datakhaos.datasource.mapper.MetaDatasourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据源服务：配置 CRUD（密码 AES 加密落库）与连接操作（测试 / 库表字段枚举 / SQL 执行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceService {

    private final MetaDatasourceMapper mapper;
    private final DataSourceConnectorFactory connectorFactory;
    private final DatasourceProperties properties;

    // ---------- 配置管理 ----------

    /** 分页查询数据源（密码不参与序列化输出） */
    public PageResult<MetaDatasource> page(long current, long size, String keyword, String dsType) {
        LambdaQueryWrapper<MetaDatasource> wrapper = new LambdaQueryWrapper<MetaDatasource>()
                .eq(StrUtil.isNotBlank(dsType), MetaDatasource::getDsType, dsType)
                .and(StrUtil.isNotBlank(keyword), filters -> filters.like(MetaDatasource::getDsName, keyword)
                        .or().like(MetaDatasource::getHost, keyword))
                .orderByDesc(MetaDatasource::getCreateTime, MetaDatasource::getId);
        Page<MetaDatasource> page = mapper.selectPage(new Page<>(current, size), wrapper);
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 数据源详情 */
    public MetaDatasource get(String id) {
        MetaDatasource ds = mapper.selectById(id);
        if (ds == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "数据源不存在: " + id);
        }
        return ds;
    }

    /** 新增数据源 */
    @Transactional(rollbackFor = Exception.class)
    public void create(MetaDatasource ds) {
        validate(ds);
        if (mapper.selectCount(new LambdaQueryWrapper<MetaDatasource>()
                .eq(MetaDatasource::getDsName, ds.getDsName())) > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "数据源名称已存在: " + ds.getDsName());
        }
        ds.setId(null);
        ds.setStatus(ds.getStatus() == null ? 1 : ds.getStatus());
        if (StrUtil.isNotBlank(ds.getPassword())) {
            ds.setPassword(EncryptUtil.encrypt(ds.getPassword(), properties.getAesKey()));
        }
        mapper.insert(ds);
    }

    /** 修改数据源：密码留空表示不修改 */
    @Transactional(rollbackFor = Exception.class)
    public void update(MetaDatasource ds) {
        if (StrUtil.isBlank(ds.getId())) {
            throw new BusinessException("数据源 ID 不能为空");
        }
        MetaDatasource exist = get(ds.getId());
        validate(ds);
        if (StrUtil.isBlank(ds.getPassword())) {
            ds.setPassword(exist.getPassword());
        } else {
            ds.setPassword(EncryptUtil.encrypt(ds.getPassword(), properties.getAesKey()));
        }
        mapper.updateById(ds);
    }

    /** 删除数据源 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        get(id);
        mapper.deleteById(id);
    }

    // ---------- 连接操作 ----------

    /** 测试连接（未持久化的配置，明文密码） */
    public boolean testConfig(DsConfig config) {
        return connectorFactory.getConnector(config.getDsType()).testConnection(config);
    }

    /** 测试连接（已保存的数据源） */
    public boolean test(String id) {
        MetaDatasource ds = get(id);
        return connectorFactory.getConnector(ds.getDsType()).testConnection(toConfig(ds));
    }

    /** 数据库列表 */
    public List<String> databases(String id) {
        MetaDatasource ds = get(id);
        return connectorFactory.getConnector(ds.getDsType()).getDatabases(toConfig(ds));
    }

    /** 表列表 */
    public List<String> tables(String id, String database) {
        MetaDatasource ds = get(id);
        return connectorFactory.getConnector(ds.getDsType()).getTables(toConfig(ds), database);
    }

    /** 字段名列表 */
    public List<String> columnNames(String id, String database, String table) {
        return columnInfos(id, database, table).stream().map(ColumnInfo::getColumnName).toList();
    }

    /** 字段详情列表 */
    public List<ColumnInfo> columnInfos(String id, String database, String table) {
        MetaDatasource ds = get(id);
        return connectorFactory.getConnector(ds.getDsType()).getColumns(toConfig(ds), database, table);
    }

    /** 执行 SQL（执行前经过 SQL 审核） */
    public QueryResult execute(String id, String sql, Map<String, Object> params) {
        MetaDatasource ds = get(id);
        String audited = SqlAuditor.audit(sql);
        try {
            return connectorFactory.getConnector(ds.getDsType())
                    .executeQuery(toConfig(ds), audited, params);
        } catch (SQLException e) {
            log.warn("SQL 执行失败 [datasource={}, type={}]: {}", id, ds.getDsType(), e.getMessage());
            throw new BusinessException("SQL 执行失败: " + e.getMessage());
        }
    }

    /** 表行数统计 */
    public long tableCount(String id, String database, String table) {
        MetaDatasource ds = get(id);
        return connectorFactory.getConnector(ds.getDsType()).getTableCount(toConfig(ds), database, table);
    }

    // ---------- 私有方法 ----------

    private void validate(MetaDatasource ds) {
        if (StrUtil.isBlank(ds.getDsName())) {
            throw new BusinessException("数据源名称不能为空");
        }
        if (StrUtil.isBlank(ds.getDsType())) {
            throw new BusinessException("数据源类型不能为空");
        }
        // 提前校验类型是否受支持，给出更友好的提示
        connectorFactory.getConnector(ds.getDsType());
        if (StrUtil.isBlank(ds.getHost())) {
            throw new BusinessException("主机地址不能为空");
        }
    }

    /** 实体 → 连接配置（解密密码） */
    private DsConfig toConfig(MetaDatasource ds) {
        DsConfig config = new DsConfig();
        config.setId(ds.getId());
        config.setDsName(ds.getDsName());
        config.setDsType(ds.getDsType());
        config.setHost(ds.getHost());
        config.setPort(ds.getPort());
        config.setDatabaseName(ds.getDatabaseName());
        config.setUsername(ds.getUsername());
        config.setPassword(EncryptUtil.decrypt(ds.getPassword(), properties.getAesKey()));
        config.setProperties(ds.getProperties());
        return config;
    }
}
