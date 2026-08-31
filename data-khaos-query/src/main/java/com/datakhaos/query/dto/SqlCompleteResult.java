package com.datakhaos.query.dto;

import lombok.Data;

import java.util.List;

/**
 * SQL 补全结果
 */
@Data
public class SqlCompleteResult {
    private List<CompletionItem> items;

    @Data
    public static class CompletionItem {
        /** 类型: KEYWORD / TABLE / COLUMN / FUNCTION */
        private String type;
        /** 显示文本 */
        private String label;
        /** 插入文本 */
        private String insertText;
        /** 描述 */
        private String detail;

        public CompletionItem() {}

        public CompletionItem(String type, String label, String insertText, String detail) {
            this.type = type;
            this.label = label;
            this.insertText = insertText;
            this.detail = detail;
        }
    }
}
