package com.datakhaos.query.dto;

public record MaterializationStatus(String assetId, String bizDate, String publicationId,
                                    String qualitySnapshotId, String publishedAt) {}
