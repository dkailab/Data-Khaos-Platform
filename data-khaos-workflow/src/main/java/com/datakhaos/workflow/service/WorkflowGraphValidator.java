package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * DAG 结构校验：保存、发布、运行前共用同一套规则，避免三处判定漂移。
 */
public final class WorkflowGraphValidator {

    private WorkflowGraphValidator() {
    }

    public static void validate(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        if (nodes == null || nodes.isEmpty()) {
            throw new BusinessException("工作流未配置任何节点，无法运行");
        }
        Set<String> codes = new HashSet<>();
        for (WorkflowNode node : nodes) {
            if (node.getNodeCode() == null || node.getNodeCode().isBlank()) {
                throw new BusinessException("存在未设置编码的节点: " + node.getNodeName());
            }
            if (!codes.add(node.getNodeCode())) {
                throw new BusinessException("节点编码重复: " + node.getNodeCode());
            }
        }
        Map<String, List<String>> adj = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (String code : codes) {
            adj.put(code, new ArrayList<>());
            indegree.put(code, 0);
        }
        if (edges != null) {
            for (WorkflowEdge edge : edges) {
                if (!codes.contains(edge.getFromCode()) || !codes.contains(edge.getToCode())) {
                    throw new BusinessException("连线引用了不存在的节点: "
                            + edge.getFromCode() + " -> " + edge.getToCode());
                }
                if (edge.getFromCode().equals(edge.getToCode())) {
                    throw new BusinessException("节点不能依赖自身: " + edge.getFromCode());
                }
                adj.get(edge.getFromCode()).add(edge.getToCode());
                indegree.put(edge.getToCode(), indegree.get(edge.getToCode()) + 1);
            }
        }
        Queue<String> queue = new ArrayDeque<>();
        indegree.forEach((code, degree) -> {
            if (degree == 0) {
                queue.offer(code);
            }
        });
        int visited = 0;
        while (!queue.isEmpty()) {
            String code = queue.poll();
            visited++;
            for (String next : adj.get(code)) {
                int degree = indegree.get(next) - 1;
                indegree.put(next, degree);
                if (degree == 0) {
                    queue.offer(next);
                }
            }
        }
        if (visited != codes.size()) {
            throw new BusinessException("工作流 DAG 存在环路，请检查连线");
        }
    }
}
