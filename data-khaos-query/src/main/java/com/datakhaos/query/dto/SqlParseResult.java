package com.datakhaos.query.dto;

import lombok.Data;

import java.util.List;

/**
 * SQL 解析结果
 */
@Data
public class SqlParseResult {
    /** SQL 中引用的表名 */
    private List<String> tables;
    /** SELECT 子句中的列引用 */
    private List<String> columns;
    /** 解析错误（如果 SQL 不合法） */
    private String parseError;
}
