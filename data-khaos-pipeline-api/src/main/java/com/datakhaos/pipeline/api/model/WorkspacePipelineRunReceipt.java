package com.datakhaos.pipeline.api.model;

public record WorkspacePipelineRunReceipt(String runId, String bizDate, String state, Integer status) {}
