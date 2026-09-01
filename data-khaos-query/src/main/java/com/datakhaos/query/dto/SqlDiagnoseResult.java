package com.datakhaos.query.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 健康诊断结果
 */
@Data
public class SqlDiagnoseResult {

    /** 是否通过（无 error 级别问题） */
    private boolean healthy;

    /** 诊断出的问题列表 */
    private List<DiagnosisIssue> issues = new ArrayList<>();

    @Data
    public static class DiagnosisIssue {
        /** 级别: info / warning / error */
        private String severity;
        /** 规则编码: SELECT_STAR / NO_WHERE / FULL_TABLE_SCAN / JOIN_NO_ON / IMPLICIT_CONVERSION */
        private String rule;
        /** 问题描述 */
        private String message;
        /** 修复建议 */
        private String suggestion;

        public DiagnosisIssue() {
        }

        public DiagnosisIssue(String severity, String rule, String message, String suggestion) {
            this.severity = severity;
            this.rule = rule;
            this.message = message;
            this.suggestion = suggestion;
        }
    }
}