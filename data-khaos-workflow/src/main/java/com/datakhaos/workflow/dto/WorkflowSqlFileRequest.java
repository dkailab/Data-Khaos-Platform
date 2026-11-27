package com.datakhaos.workflow.dto;

/**
 * SQL 文件保存请求。id 为空表示新建，否则为更新。
 */
public record WorkflowSqlFileRequest(String id, String projectId, String folder, String name,
                                     String description, String datasourceId, String content) {
}
