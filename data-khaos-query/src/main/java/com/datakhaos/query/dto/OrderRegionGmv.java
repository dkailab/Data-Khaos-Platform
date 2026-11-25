package com.datakhaos.query.dto;

import java.util.List;

public record OrderRegionGmv(String bizDate, String publicationId, String qualitySnapshotId,
                             String publishedAt, boolean truncated, List<RegionAmount> rows) {
    public record RegionAmount(String region, String gmv) {}
}
