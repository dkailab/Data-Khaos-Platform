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
    /** 归属应用；为空表示 PRC 之前由签发人委派的存量凭据，按签发人身份执行。 */
    private String appId;
    private String appName;
    private String tokenDigest;
    private String issuedBy;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private String revokedBy;
    private LocalDateTime revokedAt;
}
