package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowSqlFile;
import com.datakhaos.workflow.mapper.WorkflowSqlFileMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTaskContentResolverTest {

    @Test
    void customTaskUsesInlineSql() {
        SqlTaskContentResolver resolver = resolverFor(null);
        var resolved = resolver.resolve(Map.of("taskMode", "CUSTOM", "sql", "select 1"), "ds_node");
        assertEquals("select 1", resolved.content());
        assertEquals("ds_node", resolved.datasourceId());
        assertNull(resolved.sourceNote());
    }

    @Test
    void externalTaskReadsDraftByDefault() {
        SqlTaskContentResolver resolver = resolverFor(file());
        var resolved = resolver.resolve(Map.of(
                "taskMode", "EXTERNAL", "sqlFileId", "f1", "externalObject", "DRAFT"), null);
        assertEquals("select draft", resolved.content());
        assertEquals("ds_file", resolved.datasourceId());
        assertTrue(resolved.sourceNote().contains("草稿"));
    }

    @Test
    void latestReadsPublishedSnapshotAndNodeDatasourceWins() {
        SqlTaskContentResolver resolver = resolverFor(file());
        var resolved = resolver.resolve(Map.of(
                "taskMode", "EXTERNAL", "sqlFileId", "f1", "externalObject", "LATEST"), "ds_node");
        assertEquals("select published", resolved.content());
        assertEquals("ds_node", resolved.datasourceId());
        assertTrue(resolved.sourceNote().contains("已发布"));
    }

    @Test
    void latestFallsBackToDraftWhenNeverPublished() {
        WorkflowSqlFile file = file();
        file.setPublishedVersion(0);
        file.setPublishedContent(null);
        SqlTaskContentResolver resolver = resolverFor(file);
        var resolved = resolver.resolve(Map.of(
                "taskMode", "EXTERNAL", "sqlFileId", "f1", "externalObject", "LATEST"), null);
        assertEquals("select draft", resolved.content());
        assertTrue(resolved.sourceNote().contains("回退草稿"));
    }

    @Test
    void rejectsMissingFileAndBlankContent() {
        assertThrows(BusinessException.class, () -> resolverFor(null)
                .resolve(Map.of("taskMode", "EXTERNAL", "sqlFileId", "f1"), null));
        assertThrows(BusinessException.class, () -> resolverFor(null)
                .resolve(Map.of("taskMode", "EXTERNAL"), null));

        WorkflowSqlFile blank = file();
        blank.setDraftContent("  ");
        assertThrows(BusinessException.class, () -> resolverFor(blank)
                .resolve(Map.of("taskMode", "EXTERNAL", "sqlFileId", "f1"), null));
    }

    private WorkflowSqlFile file() {
        WorkflowSqlFile file = new WorkflowSqlFile();
        file.setId("f1");
        file.setName("ads_sale_brief.sql");
        file.setDatasourceId("ds_file");
        file.setDraftContent("select draft");
        file.setDraftVersion(2);
        file.setPublishedContent("select published");
        file.setPublishedVersion(3);
        return file;
    }

    private SqlTaskContentResolver resolverFor(WorkflowSqlFile file) {
        WorkflowSqlFileMapper mapper = (WorkflowSqlFileMapper) Proxy.newProxyInstance(
                WorkflowSqlFileMapper.class.getClassLoader(),
                new Class<?>[]{WorkflowSqlFileMapper.class},
                (proxy, method, args) -> "selectById".equals(method.getName()) ? file : null);
        return new SqlTaskContentResolver(mapper);
    }
}
