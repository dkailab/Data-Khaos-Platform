package com.datakhaos.workflow.flink;

import java.util.List;

public record FlinkDefinition(Mode mode, Integer parallelism, String sql, List<FunctionRef> functions,
                              Language language, String className, String jarUri) {
    public enum Mode { STREAMING, BATCH }

    public enum Language { JAVA, SCALA }

    public record FunctionRef(String assetId, Integer version) {}
}
