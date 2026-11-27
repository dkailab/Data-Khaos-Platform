package com.datakhaos.workflow.dto;

import java.util.List;

/**
 * 跨任务流依赖图：level=0 为当前任务流，level 越大越靠上游。
 *
 * @param rootWfId 当前任务流ID
 * @param depth    实际展开深度
 * @param nodes    任务流节点
 * @param edges    依赖边，方向为 upstream → downstream
 */
public record WorkflowDependencyGraph(String rootWfId, int depth, List<Node> nodes, List<Edge> edges) {

    public record Node(String wfId, String name, String code, Integer status, int level, boolean root) {}

    public record Edge(String fromWfId, String toWfId, String strategy) {}
}
