package com.datakhaos.query.dto;

import lombok.Data;

/**
 * SQL 补全请求
 */
@Data
public class SqlCompleteRequest {
    /** 当前 SQL 文本 */
    private String sql;

    /** 光标位置（字符偏移量） */
    private Integer cursorPosition;

    /** 数据源 ID（用于获取表名/列名） */
    private String datasourceId;

    /** 数据库名 */
    private String databaseName;
}
