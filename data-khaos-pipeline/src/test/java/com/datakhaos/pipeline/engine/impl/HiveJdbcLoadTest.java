package com.datakhaos.pipeline.engine.impl;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.datasource.api.model.JdbcConnectionUrls;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HiveJdbcLoadTest {
    @Test
    void loadsVendorDriverWithSchemaWithoutSwitchingPlatformProfile() throws Exception {
        String url = JdbcConnectionUrls.dameng("127.0.0.1:5236", "serving");
        assertEquals("jdbc:dm://127.0.0.1:5236?schema=SERVING", url);
        assertEquals("dm.jdbc.driver.DmDriver", DriverManager.getDriver(url).getClass().getName());
        assertEquals("jdbc:hive2://localhost:10000/default;auth=noSasl",
                JdbcConnectionUrls.hive("localhost:10000", "", "{\"hiveAuth\":\"NOSASL\"}"));
    }

    @Test
    void rejectsInvalidDayAndUnfilteredCustomOverwriteBeforeOpeningConnections() {
        PipelineTask task = task();
        PipelineInstance instance = new PipelineInstance();
        instance.setBizDate("2026-02-30");
        assertThrows(BusinessException.class, () -> load(task, instance));
        instance.setBizDate("2026-11-17");
        task.setSourceQuery("SELECT * FROM default.orders");
        assertThrows(BusinessException.class, () -> load(task, instance));
        task.setSourceQuery("SELECT * FROM default.orders WHERE dt = '${bizDate}'");
        assertDoesNotThrow(() -> load(task, instance));
        task.setConfig("{\"batchSize\":5001}");
        assertThrows(BusinessException.class, () -> load(task, instance));
    }

    private static HiveJdbcLoad load(PipelineTask task, PipelineInstance instance) {
        return new HiveJdbcLoad(task, instance, Map.of("ds_type", "HIVE"), Map.of("ds_type", "DM8"), "test");
    }

    private static PipelineTask task() {
        PipelineTask task = new PipelineTask();
        task.setWriteMode("REPLACE_BY_BIZ_DATE");
        task.setSourceTable("default.orders");
        task.setTargetTable("SERVING.ORDERS");
        task.setDateColumn("dt");
        return task;
    }
}
