package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("warehouse_api_version")
public class WarehouseApiVersion {
    public enum Status { DRAFT, PUBLISHED, OFFLINE }

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String apiCode;
    private int apiVersion;
    private String apiName;
    private int maxRows;
    private Status status;
    private int revision;
    private String createdBy;
    private LocalDateTime createTime;
    private String updatedBy;
    private LocalDateTime updateTime;
    private String publishedBy;
    private LocalDateTime publishedAt;
    private String offlinedBy;
    private LocalDateTime offlinedAt;
}
