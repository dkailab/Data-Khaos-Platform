package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlinkMetricsViewTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requestsOnlyAvailableTaskMetrics() throws Exception {
        assertEquals(List.of("numRecordsOutPerSecond", "backPressuredTimeMsPerSecond"),
                FlinkMetricsView.availableMetrics(json.readTree("""
                        [{"id":"backPressuredTimeMsPerSecond"},
                         {"id":"Calc[1].currentInputWatermark"},
                         {"id":"numRecordsOutPerSecond"}, {"id":"numRecordsOutPerSecond"}]
                        """)));
        assertThrows(FlinkRuntimeClient.RuntimeFailure.class,
                () -> FlinkMetricsView.availableMetrics(json.readTree("{}")));
    }

    @Test
    void keepsZeroAndProjectsOnlyExpectedAggregations() throws Exception {
        var sample = FlinkMetricsView.sample(json.readTree("""
                [{"id":"numRecordsInPerSecond","sum":0,"avg":8},
                 {"id":"numRecordsOutPerSecond","sum":14.5},
                 {"id":"backPressuredTimeMsPerSecond","max":630,"avg":314},
                 {"id":"busyTimeMsPerSecond","avg":"NaN"},
                 {"id":"idleTimeMsPerSecond","avg":750},
                 {"id":"currentInputWatermark","min":1763900000000}]
                """));
        assertNotNull(sample);
        assertEquals(0d, sample.recordsInPerSecond());
        assertEquals(14.5, sample.recordsOutPerSecond());
        assertEquals(630d, sample.maxBackpressureMsPerSecond());
        assertNull(sample.avgBusyMsPerSecond());
        assertEquals(750d, sample.avgIdleMsPerSecond());
        assertEquals(Instant.ofEpochMilli(1763900000000L), sample.inputWatermark());
    }

    @Test
    void leavesAbsentAndInvalidSamplesUnknown() throws Exception {
        assertNull(FlinkMetricsView.sample(json.readTree("[]")));
        assertNull(FlinkMetricsView.sample(json.readTree("""
                [{"id":"currentInputWatermark","min":-9223372036854775808},
                 {"id":"busyTimeMsPerSecond","avg":true},
                 {"id":"backPressuredTimeMsPerSecond","max":1100}]
                """)));
        assertNull(FlinkMetricsView.sample(json.readTree("""
                [{"id":"currentInputWatermark","min":9223372036854775807}]
                """)));
    }
}
