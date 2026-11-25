package com.datakhaos.workflow.flink;

import java.time.Duration;

public record FlinkEventTimeDiagnosis(State state, Long inputDelta, int stalledIntervals) {
    public enum State { BASELINE, PROGRESSING, IDLE, WAITING, STALLED, UNKNOWN }

    public static FlinkEventTimeDiagnosis compare(FlinkEventTimeSample previous, FlinkEventTimeSample current, int streak) {
        if (!usable(current)) return new FlinkEventTimeDiagnosis(State.UNKNOWN, null, 0);
        if (!usable(previous) || !current.vertexId().equals(previous.vertexId())
                || !current.vertexStartedAt().equals(previous.vertexStartedAt())
                || current.parallelism() != previous.parallelism()) {
            return new FlinkEventTimeDiagnosis(State.BASELINE, null, 0);
        }
        long elapsed = Duration.between(previous.sampledAt(), current.sampledAt()).toMillis();
        long delta = current.inputRecords() - previous.inputRecords();
        int progress = current.inputWatermark().compareTo(previous.inputWatermark());
        if (elapsed < 20_000 || elapsed > 120_000 || delta < 0 || progress < 0) {
            return new FlinkEventTimeDiagnosis(State.BASELINE, null, 0);
        }
        if (progress > 0) return new FlinkEventTimeDiagnosis(State.PROGRESSING, delta, 0);
        if (delta == 0) return new FlinkEventTimeDiagnosis(State.IDLE, 0L, 0);
        int next = Math.min(streak + 1, 3);
        return new FlinkEventTimeDiagnosis(next == 3 ? State.STALLED : State.WAITING, delta, next);
    }

    public static boolean usable(FlinkEventTimeSample sample) {
        return sample != null && "RUNNING".equals(sample.status()) && sample.vertexStartedAt() != null
                && sample.parallelism() > 0 && sample.inputRecords() != null && sample.inputWatermark() != null;
    }
}
