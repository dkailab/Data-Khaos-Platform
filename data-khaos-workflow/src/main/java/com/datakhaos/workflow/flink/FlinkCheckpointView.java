package com.datakhaos.workflow.flink;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record FlinkCheckpointView(boolean available, Counts counts, Entry latestCompleted,
                                  Entry latestFailed, Restore latestRestore, List<Entry> recent) {
    private static final int MAX_HISTORY = 12;

    static FlinkCheckpointView from(JsonNode payload) {
        JsonNode stats = payload.path("counts");
        if (!stats.isObject()) {
            return new FlinkCheckpointView(false, null, null, null, null, List.of());
        }
        JsonNode latest = payload.path("latest");
        JsonNode restored = latest.path("restored");
        List<Entry> history = new ArrayList<>();
        for (JsonNode node : payload.path("history")) {
            if (history.size() >= MAX_HISTORY) break;
            Entry entry = entry(node);
            if (entry != null) history.add(entry);
        }
        return new FlinkCheckpointView(true,
                new Counts(number(stats, "total"), number(stats, "in_progress"), number(stats, "completed"),
                        number(stats, "failed"), number(stats, "restored")),
                entry(latest.path("completed")), entry(latest.path("failed")),
                number(restored, "id") == null ? null : new Restore(number(restored, "id"),
                        time(restored, "restore_timestamp"), restored.path("is_savepoint").asBoolean()),
                List.copyOf(history));
    }

    private static Entry entry(JsonNode node) {
        Long id = number(node, "id");
        String status = node.path("status").asText();
        if (id == null || !List.of("COMPLETED", "FAILED", "IN_PROGRESS").contains(status)) return null;
        return new Entry(id, status, node.path("is_savepoint").asBoolean(), time(node, "trigger_timestamp"),
                number(node, "end_to_end_duration"), number(node, "checkpointed_size"));
    }

    private static Long number(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 ? value.asLong() : null;
    }

    private static Instant time(JsonNode node, String field) {
        Long millis = number(node, field);
        return millis == null || millis == 0 ? null : Instant.ofEpochMilli(millis);
    }

    public record Counts(Long total, Long inProgress, Long completed, Long failed, Long restored) {}

    public record Entry(long id, String status, boolean savepoint, Instant triggeredAt, Long durationMs,
                        Long checkpointedBytes) {}

    public record Restore(long id, Instant restoredAt, boolean savepoint) {}
}
