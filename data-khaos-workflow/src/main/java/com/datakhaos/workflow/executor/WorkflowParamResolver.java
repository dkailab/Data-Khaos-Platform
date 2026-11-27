package com.datakhaos.workflow.executor;

import java.util.Map;

/**
 * {@code ${param}} 参数替换工具，供节点上下文构建与脚本执行复用。
 */
public final class WorkflowParamResolver {

    private WorkflowParamResolver() {
    }

    public static String resolve(String template, Map<String, Object> params) {
        if (template == null || params == null || params.isEmpty()) {
            return template;
        }
        String out = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            out = out.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
        return out;
    }
}
