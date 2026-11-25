package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("warehouse_api_credential")
public class WarehouseApiCredential {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String apiVersionId;
    private String appName;
    private String tokenDigest;
    private String issuedBy;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private String revokedBy;
    private LocalDateTime revokedAt;
}
