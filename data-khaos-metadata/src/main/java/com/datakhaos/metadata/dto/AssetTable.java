package com.datakhaos.metadata.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AssetTable {
    private String id;
    private String datasourceId;
    private String databaseName;
    private String tableName;
    private String tableType;
    private String description;
    private LocalDateTime syncTime;
}
