package com.datakhaos.dquality.service;

import com.datakhaos.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 管道阶段计数对账的纯计算：不依赖数据源连接，覆盖丢数、重复、排序与采样不一致。 */
class QualityEnginePipelineCountTest {

    private static Map<String, Object> stage(String name, int order, long in, long out) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", name);
        row.put("stage_order", order);
        row.put("records_in", in);
        row.put("records_out", out);
        return row;
    }

    @Test
    void passesWhenAdjacentStagesAreComplete() {
        var verdict = QualityEngine.pipelineVerdict(List.of(
                stage("source", 0, 100, 100),
                stage("dwd", 1, 100, 100),
                stage("ads", 2, 100, 100)), new BigDecimal("0.01"));

        assertTrue(verdict.passed());
        assertFalse(verdict.inconsistent());
        assertEquals(100, verdict.sourceIn());
        assertEquals(0, BigDecimal.ZERO.compareTo(verdict.driftRate()));
    }

    @Test
    void failsAndLocatesStageWhenRecordsAreLost() {
        var verdict = QualityEngine.pipelineVerdict(List.of(
                stage("source", 0, 100, 100),
                stage("dwd", 1, 95, 95),
                stage("ads", 2, 95, 95)), new BigDecimal("0.01"));

        assertFalse(verdict.passed());
        assertEquals(5, verdict.lost());
        assertEquals(0, new BigDecimal("0.0500").compareTo(verdict.driftRate()));
        assertTrue(verdict.message().contains("source→dwd 丢 5"));
        assertEquals(5L, verdict.details().get(0).get("loss"));
    }

    /** 下游比上游多（重复写入）不能当 0 偏差通过。 */
    @Test
    void countsDuplicatesAsDrift() {
        var verdict = QualityEngine.pipelineVerdict(List.of(
                stage("source", 0, 100, 100),
                stage("ads", 1, 120, 120)), new BigDecimal("0.01"));

        assertFalse(verdict.passed());
        assertEquals(0, verdict.lost());
        assertEquals(20, verdict.duplicated());
        assertEquals(0, new BigDecimal("0.2000").compareTo(verdict.driftRate()));
        assertTrue(verdict.message().contains("重 20"));
    }

    /** 采样 SQL 的返回顺序不可信，有 stage_order 时必须按拓扑顺序重排。 */
    @Test
    void ordersRowsByStageOrder() {
        var rows = new ArrayList<Map<String, Object>>();
        rows.add(stage("ads", 2, 95, 95));
        rows.add(stage("source", 0, 100, 100));
        rows.add(stage("dwd", 1, 95, 95));

        var verdict = QualityEngine.pipelineVerdict(rows, new BigDecimal("0.01"));

        assertEquals(100, verdict.sourceIn());
        assertEquals(5, verdict.lost());
    }

    /** 源端为 0 但下游有数据：批次不完整，不能判通过，也不能只报一个 0 偏差。 */
    @Test
    void flagsInconsistentBatchWhenSourceIsZeroButDownstreamHasData() {
        var verdict = QualityEngine.pipelineVerdict(List.of(
                stage("source", 0, 0, 0),
                stage("ads", 1, 50, 50)), new BigDecimal("0.01"));

        assertFalse(verdict.passed());
        assertTrue(verdict.inconsistent());
        assertTrue(verdict.message().contains("采样不一致"));
    }

    @Test
    void passesEmptyPipeline() {
        var verdict = QualityEngine.pipelineVerdict(List.of(
                stage("source", 0, 0, 0),
                stage("ads", 1, 0, 0)), BigDecimal.ZERO);

        assertTrue(verdict.passed());
        assertFalse(verdict.inconsistent());
    }

    @Test
    void rejectsMetricsSqlWithoutCountColumns() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("stage", "source");

        assertThrows(BusinessException.class,
                () -> QualityEngine.pipelineVerdict(List.of(row), BigDecimal.ZERO));
    }
}
