package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlinkCheckpointViewTest {
    @Test
    void projectsCheckpointAndRestoreEvidenceWithoutEnginePathsOrErrors() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var view = FlinkCheckpointView.from(mapper.readTree("""
                {"counts":{"total":3,"completed":1,"failed":1,"restored":1,"in_progress":1},
                 "latest":{"completed":{"id":1,"status":"COMPLETED","checkpointed_size":4096,
                     "trigger_timestamp":1700000000000,"external_path":"file:/private"},
                   "failed":{"id":2,"status":"FAILED","end_to_end_duration":-1,"failure_message":"private"},
                   "restored":{"id":17,"is_savepoint":true,"restore_timestamp":1700000010000,"external_path":"file:/private"}},
                 "history":[{"id":3,"status":"IN_PROGRESS","end_to_end_duration":-1}]}
                """));
        assertTrue(view.available());
        assertEquals(1L, view.counts().failed());
        assertEquals(4096L, view.latestCompleted().checkpointedBytes());
        assertNull(view.latestFailed().durationMs());
        assertTrue(view.latestRestore().savepoint());
        assertEquals(3, view.recent().get(0).id());
        assertFalse(mapper.writeValueAsString(view).contains("private"));
    }

    @Test
    void distinguishesMissingStatisticsFromAnEmptyHistory() throws Exception {
        var mapper = new ObjectMapper();
        assertFalse(FlinkCheckpointView.from(mapper.readTree("{}")).available());
        var empty = FlinkCheckpointView.from(mapper.readTree("{\"counts\":{\"completed\":0}}"));
        assertTrue(empty.available());
        assertEquals(0L, empty.counts().completed());
        assertEquals(0, empty.recent().size());
    }
}
