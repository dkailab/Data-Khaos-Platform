package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlinkDefinitionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void historicDefinitionsRemainReadable() throws Exception {
        var function = json.readValue("""
                {"className":"example.Score","language":"JAVA","jarUri":"file:///artifacts/score.jar"}
                """, FlinkDefinition.class);
        assertNull(function.functionType());
        assertEquals("example.Score", function.className());

        var job = json.readValue("""
                {"mode":"STREAMING","parallelism":2,"sql":"INSERT INTO sink SELECT * FROM source","functions":[]}
                """, FlinkDefinition.class);
        assertNull(job.jarJob());
        assertEquals(List.of(), job.functions());
    }

    @Test
    void keepsJarArgumentsAsAnOrderedList() throws Exception {
        var job = json.readValue("""
                {"mode":"BATCH","parallelism":1,"jarJob":{
                  "jarId":"fixture_1.jar","entryClass":"example.Main","arguments":["--name","order batch","","a,b"]
                }}
                """, FlinkDefinition.class);
        assertEquals(List.of("--name", "order batch", "", "a,b"), job.jarJob().arguments());
        assertEquals(job, json.readValue(json.writeValueAsString(job), FlinkDefinition.class));
    }

    @Test
    void jarIdMustBeASingleSafePathSegment() {
        assertTrue(FlinkRuntimeClient.validJarId("7959ed-d6_orders-1.2.jar"));
        assertFalse(FlinkRuntimeClient.validJarId("../orders.jar"));
        assertFalse(FlinkRuntimeClient.validJarId("orders%2Fother.jar"));
        assertFalse(FlinkRuntimeClient.validJarId("orders.jar?override=x"));
        assertFalse(FlinkRuntimeClient.validJarId("orders.jar#fragment"));
    }
}
