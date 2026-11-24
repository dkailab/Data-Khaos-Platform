package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class FlinkRuntimeClientTest {
    @Test
    void expiredInsertBudgetFailsBeforeDispatch() {
        var client = new FlinkRuntimeClient(new ObjectMapper(), "project", "", "");

        assertThrows(FlinkRuntimeClient.SubmitNotSent.class,
                () -> client.submitInsert("unused-session", "INSERT INTO target SELECT * FROM source", System.nanoTime()));
    }
}
