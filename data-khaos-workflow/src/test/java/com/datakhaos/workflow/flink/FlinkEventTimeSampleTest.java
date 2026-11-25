package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FlinkEventTimeSampleTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant sampled = Instant.ofEpochMilli(1_764_116_000_000L);

    @Test
    void preservesRealZeroAndProjectsSlowestInputWatermark() throws Exception {
        var sample = FlinkEventTimeSample.from(json.readTree("""
                {"id":"vertex","status":"RUNNING","parallelism":2,"start-time":1764110000000,
                 "metrics":{"read-records":0,"read-records-complete":true}}
                """), json.readTree("""
                [{"id":"currentInputWatermark","min":1764115000000,"max":1764115900000}]
                """), sampled);
        assertEquals(0L, sample.inputRecords());
        assertEquals(2, sample.parallelism());
        assertEquals(Instant.ofEpochMilli(1_764_115_000_000L), sample.inputWatermark());
        assertEquals(sampled, sample.sampledAt());
    }

    @Test
    void partialCountersAndUninitializedWatermarksAreUnknown() throws Exception {
        var sample = FlinkEventTimeSample.from(json.readTree("""
                {"id":"vertex","status":"RUNNING","metrics":{"read-records":300,"read-records-complete":false}}
                """), json.readTree("""
                [{"id":"currentInputWatermark","min":-9223372036854775808}]
                """), sampled);
        assertNull(sample.inputRecords());
        assertNull(sample.inputWatermark());
        assertNull(sample.vertexStartedAt());
    }
}
