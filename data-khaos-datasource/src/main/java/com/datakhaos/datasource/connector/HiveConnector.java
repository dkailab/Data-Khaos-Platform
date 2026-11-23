package com.datakhaos.datasource.connector;

import com.datakhaos.datasource.api.model.DsConfig;
import com.datakhaos.datasource.api.model.JdbcConnectionUrls;
import org.springframework.stereotype.Component;

/**
 * Apache Hive 连接器（HiveServer2 协议）。
 */
@Component
public class HiveConnector extends AbstractJdbcConnector {

    @Override
    public String getType() {
        return "HIVE";
    }

    @Override
    public String getDriverClass() {
        return "org.apache.hive.jdbc.HiveDriver";
    }

    @Override
    public String buildJdbcUrl(DsConfig config) {
        return JdbcConnectionUrls.hive(hostPort(config, 10000), config.getDatabaseName(), config.getProperties());
    }
}
