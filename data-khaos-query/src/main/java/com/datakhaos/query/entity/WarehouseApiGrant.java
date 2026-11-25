package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 应用授权：以 appId + apiVersionId 为唯一键，携带配额与过期时间。
 */
@Data
@TableName("warehouse_api_grant")
public class WarehouseApiGrant {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String appId;
    private String apiVersionId;
    /** 每自然分钟调用上限。 */
    private int quotaPerMinute;
    private LocalDateTime expiresAt;
    private String revokedBy;
    private LocalDateTime revokedAt;
    private String createdBy;
    private LocalDateTime createTime;
    private String updatedBy;
    private LocalDateTime updateTime;
}
