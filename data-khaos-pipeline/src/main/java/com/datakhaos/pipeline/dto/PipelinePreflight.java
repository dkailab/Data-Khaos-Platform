package com.datakhaos.pipeline.dto;

import java.util.List;

/** Read-only validation of the saved load against the selected business date. */
public record PipelinePreflight(String targetDatabaseName, String targetTable, String bizDate, String writeMode,
                                int batchSize, boolean sampleFound, List<Mapping> mappings, List<String> warnings) {

    public record Mapping(String sourceColumn, String sourceType, String targetColumn, String targetType,
                          boolean partitionColumn) {}
}
