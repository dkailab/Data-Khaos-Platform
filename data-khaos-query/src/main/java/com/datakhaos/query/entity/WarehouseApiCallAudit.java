package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 调用审计：只保留调用元数据，凭据与原始业务结果留在调用链之外。
 */
@Data
@TableName("warehouse_api_call_audit")
public class WarehouseApiCallAudit {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    /** 供按次追溯授权结果、API 版本与数据发布证据。 */
    private String requestId;
    private String projectId;
    private String appId;
    private String credentialId;
    private String apiVersionId;
    private String apiCode;
    private Integer apiVersion;
    private String bizDate;
    private int resultCode;
    private long costMs;
    private String publicationId;
    private String qualitySnapshotId;
    /** 该次调用实际返回的时效状态，供事后复核实时结果的新鲜度。 */
    private String freshness;
    private LocalDateTime calledAt;
}
