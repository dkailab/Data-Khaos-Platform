package com.datakhaos.datasource.connector;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.datasource.api.model.ColumnInfo;
import com.datakhaos.datasource.api.model.DsConfig;
import com.datakhaos.datasource.api.model.JdbcConnectionUrls;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 达梦 DM8 连接器（国产化）。数据源的库名字段用于选择模式。
 */
@Component
public class DmConnector extends AbstractJdbcConnector {

    @Override
    public String getType() {
        return "DM8";
    }

    @Override
    public String getDriverClass() {
        return "dm.jdbc.driver.DmDriver";
    }

    @Override
    public String buildJdbcUrl(DsConfig config) {
        return JdbcConnectionUrls.dameng(hostPort(config, 5236), config.getDatabaseName());
    }

    @Override
    protected String metadataCatalog(String database) {
        return null;
    }

    @Override
    public List<String> getTables(DsConfig config, String database) {
        return super.getTables(config, schema(config, database));
    }

    @Override
    public List<ColumnInfo> getColumns(DsConfig config, String database, String table) {
        return super.getColumns(config, schema(config, database), JdbcConnectionUrls.damengIdentifier(table));
    }

    @Override
    public long getTableCount(DsConfig config, String database, String table) {
        return super.getTableCount(config, database, schema(config, database) + "." + JdbcConnectionUrls.damengIdentifier(table));
    }

    private String schema(DsConfig config, String database) {
        return JdbcConnectionUrls.damengIdentifier(StrUtil.blankToDefault(database,
                StrUtil.blankToDefault(config.getDatabaseName(), config.getUsername())));
    }
}
