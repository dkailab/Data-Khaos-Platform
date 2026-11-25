package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkMetricsViewTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void requestsOnlySupportedDiscoveredMetrics() throws Exception {
        assertEquals("numRecordsOutPerSecond,backPressuredTimeMsPerSecond,"
                        + "Calc%5B1%5D.currentInputWatermark,%E8%AE%A2%E5%8D%95%26Source.KafkaSourceReader.KafkaConsumer.records-lag-max",
                FlinkMetricsView.availableMetrics(json.readTree("""
                        [{"id":"backPressuredTimeMsPerSecond"},
                         {"id":"Calc[1].currentInputWatermark"},
                         {"id":"订单&Source.KafkaSourceReader.KafkaConsumer.records-lag-max"},
                         {"id":"source.topic.partition.records-lag-max"},
                         {"id":"numRecordsOutPerSecond"}, {"id":"numRecordsOutPerSecond"}]
                        """)).get());
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
                """), false);
        assertNotNull(sample);
        assertEquals(0d, sample.recordsInPerSecond());
        assertEquals(14.5, sample.recordsOutPerSecond());
        assertEquals(630d, sample.maxBackpressureMsPerSecond());
        assertNull(sample.avgBusyMsPerSecond());
        assertEquals(750d, sample.avgIdleMsPerSecond());
        assertEquals(Instant.ofEpochMilli(1763900000000L), sample.inputWatermark());
    }

    @Test
    void groupsChainWatermarksAndKafkaLagByOperator() throws Exception {
        var sample = FlinkMetricsView.sample(json.readTree("""
                [{"id":"Calc[2].currentInputWatermark","min":1763900000000,"max":1763900005000},
                 {"id":"Calc[2].currentOutputWatermark","min":1763899999000},
                 {"id":"Source__orders[1].KafkaSourceReader.KafkaConsumer.records-lag-max","max":0,"sum":40},
                 {"id":"Source__orders[1].currentOutputWatermark","min":-9223372036854775808},
                 {"id":"Source__orders[1].pendingRecords","min":0,"sum":16},
                 {"id":"Source__payments[3].pendingRecords","min":-1,"sum":50},
                 {"id":"Source__payments[3].KafkaSourceReader.KafkaConsumer.records-lag-max","max":42}]
                """), false);
        assertNotNull(sample);
        assertEquals(3, sample.operators().size());
        var calc = sample.operators().get(0);
        assertEquals("Calc[2]", calc.name());
        assertEquals(Instant.ofEpochMilli(1763900000000L), calc.inputWatermark());
        assertEquals(Instant.ofEpochMilli(1763899999000L), calc.outputWatermark());
        assertNull(calc.kafkaMaxLag());
        assertNull(sample.operators().get(1).outputWatermark());
        assertEquals(0d, sample.operators().get(1).kafkaMaxLag());
        assertEquals(16d, sample.operators().get(1).pendingRecords());
        assertEquals(42d, sample.operators().get(2).kafkaMaxLag());
        assertNull(sample.operators().get(2).pendingRecords());
    }

    @Test
    void keepsTaskMetricsWhenOperatorNamesCannotFitOneQuery() {
        var ids = json.createArrayNode();
        ids.addObject().put("id", "currentInputWatermark");
        ids.addObject().put("id", "订单".repeat(400) + ".currentOutputWatermark");
        ids.addObject().put("id", "Calc,Filter.currentOutputWatermark");
        ids.addObject().put("id", "Filter.currentOutputWatermark");
        var query = FlinkMetricsView.availableMetrics(ids);
        assertEquals("currentInputWatermark,Filter.currentOutputWatermark", query.get());
        assertTrue(query.operatorMetricsTruncated());
    }

    @Test
    void kafkaMonitoringRequiresEveryDiscoveredLagGaugeAndPreservesZero() throws Exception {
        var query = FlinkMetricsView.kafkaMetrics(json.readTree("""
                [{"id":"Source[1].KafkaSourceReader.KafkaConsumer.records-lag-max"},
                 {"id":"Source[2].KafkaSourceReader.KafkaConsumer.records-lag-max"},
                 {"id":"Source[1].pendingRecords"},{"id":"backPressuredTimeMsPerSecond"}]
                """));
        assertEquals(2, query.get().split(",").length);
        assertNull(FlinkMetricsView.kafkaLag(json.readTree("""
                [{"id":"Source[1].KafkaSourceReader.KafkaConsumer.records-lag-max","max":0}]
                """), query));
        assertNull(FlinkMetricsView.kafkaLag(json.readTree("""
                [{"id":"Source[1].KafkaSourceReader.KafkaConsumer.records-lag-max","max":0},
                 {"id":"Source[2].KafkaSourceReader.KafkaConsumer.records-lag-max","max":"NaN"}]
                """), query));
        assertEquals(0L, FlinkMetricsView.kafkaLag(json.readTree("""
                [{"id":"Source[1].KafkaSourceReader.KafkaConsumer.records-lag-max","max":0},
                 {"id":"Source[2].KafkaSourceReader.KafkaConsumer.records-lag-max","max":0}]
                """), query));
        assertEquals(12500L, FlinkMetricsView.kafkaLag(json.readTree("""
                [{"id":"Source[1].KafkaSourceReader.KafkaConsumer.records-lag-max","max":12500},
                 {"id":"Source[2].KafkaSourceReader.KafkaConsumer.records-lag-max","max":2000}]
                """), query));
    }

    @Test
    void leavesAbsentAndInvalidSamplesUnknown() throws Exception {
        assertNull(FlinkMetricsView.sample(json.readTree("[]"), false));
        assertNull(FlinkMetricsView.sample(json.readTree("""
                [{"id":"currentInputWatermark","min":-9223372036854775808},
                 {"id":"busyTimeMsPerSecond","avg":true},
                 {"id":"backPressuredTimeMsPerSecond","max":1100}]
                """), false));
        assertNull(FlinkMetricsView.sample(json.readTree("""
                [{"id":"currentInputWatermark","min":9223372036854775807}]
                """), false));
    }
}
