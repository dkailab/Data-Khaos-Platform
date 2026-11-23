package com.datakhaos.workflow.flink;

import com.datakhaos.workflow.entity.FlinkAsset;
import com.datakhaos.workflow.entity.FlinkAssetVersion;

import java.time.LocalDateTime;

public record FlinkVersionView(String assetId, String code, FlinkAsset.Kind kind, int currentVersion,
                              Revision revision, FlinkDefinition definition) {
    public record Revision(int version, String name, String note, String createdBy, Integer restoredFrom,
                           LocalDateTime createTime) {
        public static Revision of(FlinkAssetVersion row) {
            return new Revision(row.getVersionNumber(), row.getName(), row.getNote(), row.getCreatedBy(),
                    row.getRestoredFrom(), row.getCreateTime());
        }
    }
}
