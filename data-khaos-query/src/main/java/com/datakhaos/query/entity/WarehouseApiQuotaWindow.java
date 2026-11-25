package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 配额窗口：按分钟固定窗口计数，落在共享库上，多实例共享同一份额度。
 */
@Data
@TableName("warehouse_api_quota_window")
public class WarehouseApiQuotaWindow {
    @TableId(type = IdType.INPUT)
    private String id;
    private String appId;
    private String apiVersionId;
    /** epoch 分钟数。 */
    private long windowStart;
    private int usedCount;
}
