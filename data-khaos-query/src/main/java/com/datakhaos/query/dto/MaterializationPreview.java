package com.datakhaos.query.dto;

import com.datakhaos.datasource.api.model.QueryResult;

public record MaterializationPreview(String tableName, String bizDate, String publicationId,
                                     String qualitySnapshotId, String publishedAt, boolean cacheHit, QueryResult result) {}
