package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowSqlFileFolderTest {

    @Test
    void defaultsBlankFolder() {
        assertEquals("/SQL文件", WorkflowSqlFileService.normalizeFolder(null));
        assertEquals("/SQL文件", WorkflowSqlFileService.normalizeFolder("  "));
    }

    @Test
    void normalizesSeparatorsAndTrailingSlash() {
        assertEquals("/SQL文件/ADS", WorkflowSqlFileService.normalizeFolder("/SQL文件/ADS/"));
        assertEquals("/SQL/ADS", WorkflowSqlFileService.normalizeFolder("\\SQL\\ADS"));
        assertEquals("/", WorkflowSqlFileService.normalizeFolder("/"));
    }

    @Test
    void rejectsOverlongFolder() {
        assertThrows(BusinessException.class,
                () -> WorkflowSqlFileService.normalizeFolder("/" + "a".repeat(250)));
    }
}
