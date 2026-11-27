package com.datakhaos.workflow.service;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.workflow.entity.WorkflowEdge;
import com.datakhaos.workflow.entity.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowGraphValidatorTest {

    @Test
    void acceptsValidDag() {
        assertDoesNotThrow(() -> WorkflowGraphValidator.validate(
                List.of(node("a"), node("b"), node("c")),
                List.of(edge("a", "b"), edge("a", "c"), edge("b", "c"))));
    }

    @Test
    void rejectsEmptyGraph() {
        assertThrows(BusinessException.class, () -> WorkflowGraphValidator.validate(List.of(), List.of()));
    }

    @Test
    void rejectsDuplicateCodes() {
        assertThrows(BusinessException.class,
                () -> WorkflowGraphValidator.validate(List.of(node("a"), node("a")), List.of()));
    }

    @Test
    void rejectsMissingEndpoint() {
        assertThrows(BusinessException.class,
                () -> WorkflowGraphValidator.validate(List.of(node("a")), List.of(edge("a", "b"))));
    }

    @Test
    void rejectsSelfDependency() {
        assertThrows(BusinessException.class,
                () -> WorkflowGraphValidator.validate(List.of(node("a")), List.of(edge("a", "a"))));
    }

    @Test
    void rejectsCycle() {
        assertThrows(BusinessException.class, () -> WorkflowGraphValidator.validate(
                List.of(node("a"), node("b"), node("c")),
                List.of(edge("a", "b"), edge("b", "c"), edge("c", "a"))));
    }

    private WorkflowNode node(String code) {
        WorkflowNode node = new WorkflowNode();
        node.setNodeCode(code);
        node.setNodeName(code);
        node.setNodeType("SQL");
        return node;
    }

    private WorkflowEdge edge(String from, String to) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setFromCode(from);
        edge.setToCode(to);
        return edge;
    }
}
