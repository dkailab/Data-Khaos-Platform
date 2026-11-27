package com.datakhaos.workflow.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * SQL 文件资源：供数据开发工作台保存可复用的 SQL，任务节点可以「外部任务」方式引用。
 *
 * <p>草稿与已发布内容分离：
 * <ul>
 *   <li>{@code draftContent} 编辑中的内容，保存即更新；</li>
 *   <li>{@code publishedContent} 发布快照，外部任务选择「最新版本」时读取它。</li>
 * </ul>
 * 未发布过（{@code publishedVersion=0}）时，「最新版本」回退为草稿内容，避免节点无内容可跑。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("workflow_sql_file")
public class WorkflowSqlFile extends WorkflowBaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    /** 所属项目组ID */
    private String projectId;

    /** 资源目录，以 / 开头，如 /SQL文件/ADS */
    private String folder;

    /** 文件名 */
    private String name;

    /** 说明 */
    private String description;

    /** 默认数据源ID（节点未指定时使用） */
    private String datasourceId;

    /** 草稿内容 */
    private String draftContent;

    /** 已发布内容快照 */
    private String publishedContent;

    /** 草稿版本号，每次保存递增 */
    private Integer draftVersion;

    /** 已发布版本号，0 表示未发布 */
    private Integer publishedVersion;

    /** 最后修改人ID */
    private String updatedBy;
}
