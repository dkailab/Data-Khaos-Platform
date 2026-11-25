package com.datakhaos.query.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数仓 API 应用：项目内稳定身份，承载调用凭据、授权与配额。
 */
@Data
@TableName("warehouse_api_app")
public class WarehouseApiApp {
    public enum Status { ENABLED, DISABLED }

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String projectId;
    private String appName;
    /** 负责人：运维意义上的归属，不参与执行。 */
    private String owner;
    /** 运行主体：以该 permission 账号执行查询，决定数据可见范围。 */
    private String runtimeSubject;
    private Status status;
    private int revision;
    private String createdBy;
    private LocalDateTime createTime;
    private String updatedBy;
    private LocalDateTime updateTime;
}
