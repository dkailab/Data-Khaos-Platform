package com.datakhaos.workflow.dto;

/**
 * 单节点调试结果（同步执行，不写入运行实例）。
 */
public record NodeDebugResult(boolean success, int rows, long durationMs, String log, String error) {
}
