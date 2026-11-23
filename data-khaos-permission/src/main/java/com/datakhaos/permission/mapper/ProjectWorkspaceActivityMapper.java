package com.datakhaos.permission.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceRunDetailDto;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDetailDto;

import java.time.LocalDateTime;
import java.util.List;
import com.datakhaos.permission.api.model.ProjectWorkspaceTaskDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 工作区列表只读取共享元数据库中的展示字段。执行仍由各业务服务负责。 */
@Mapper
public interface ProjectWorkspaceActivityMapper {

    @Select("""
            <script>
            SELECT id, kind, name, status, updated_at
            FROM (
              SELECT id, 'WORKFLOW' AS kind, name,
                     CASE status WHEN 0 THEN 'DISABLED' WHEN 1 THEN 'ENABLED'
                          WHEN 2 THEN 'PUBLISHED' ELSE 'UNKNOWN' END AS status,
                     COALESCE(update_time, create_time) AS updated_at
              FROM workflow_def WHERE project_id = #{projectId} AND deleted = 0
              UNION ALL
              SELECT id, 'PIPELINE' AS kind, task_name AS name,
                     CASE status WHEN 0 THEN 'DISABLED' WHEN 1 THEN 'ENABLED' ELSE 'UNKNOWN' END AS status,
                     COALESCE(update_time, create_time) AS updated_at
              FROM pipeline_task WHERE project_group_id = #{projectId}
            ) activity
            <where>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="search != null">AND LOWER(name) LIKE #{search} ESCAPE '!'</if>
            </where>
            ORDER BY updated_at DESC, kind, id DESC
            </script>
            """)
    IPage<ProjectWorkspaceTaskDto> tasks(IPage<ProjectWorkspaceTaskDto> page, @Param("projectId") String projectId,
                                        @Param("kind") String kind, @Param("search") String search);

    @Select("""
            <script>
            SELECT id, kind, task_id, task_name, status, biz_date, started_at, duration_ms, trigger_type, alert_acknowledged_at
            FROM (
              SELECT id, 'WORKFLOW' AS kind, wf_id AS task_id, wf_name AS task_name,
                     run_status AS status, biz_date, COALESCE(start_time, create_time) AS started_at,
                     duration_ms, trigger_type, alert_acknowledged_at
              FROM workflow_run WHERE project_group_id = #{projectId}
              UNION ALL
              SELECT r.id, 'PIPELINE' AS kind, r.task_id, t.task_name,
                     CASE r.status WHEN 0 THEN 'RUNNING' WHEN 1 THEN 'SUCCESS'
                          WHEN 2 THEN 'FAILED' WHEN 3 THEN 'RECOVERY_REQUIRED' ELSE 'UNKNOWN' END AS status,
                     r.biz_date, COALESCE(r.start_time, r.create_time) AS started_at,
                     r.duration_ms, r.trigger_type, NULL AS alert_acknowledged_at
              FROM pipeline_instance r
              LEFT JOIN pipeline_task t ON r.task_id = t.id AND t.project_group_id = r.project_group_id
              WHERE r.project_group_id = #{projectId}
            ) activity
            <where>
              <if test="kind != null">AND kind = #{kind}</if>
              <if test="bizDate != null">AND biz_date = #{bizDate}</if>
              <if test="status != null">AND status = #{status}</if>
              <if test="taskId != null">AND task_id = #{taskId}</if>
              <if test="triggerType != null">AND trigger_type = #{triggerType}</if>
              <if test="alertsOnly">AND kind = 'WORKFLOW' AND status IN ('FAILED', 'RECOVERY_REQUIRED')</if>
              <if test="unacknowledgedOnly">AND alert_acknowledged_at IS NULL</if>
            </where>
            ORDER BY started_at DESC, kind, id DESC
            </script>
            """)
    IPage<ProjectWorkspaceRunDto> runs(IPage<ProjectWorkspaceRunDto> page, @Param("projectId") String projectId,
                                      @Param("kind") String kind, @Param("bizDate") String bizDate,
                                      @Param("status") String status, @Param("taskId") String taskId,
                                      @Param("triggerType") String triggerType, @Param("alertsOnly") boolean alertsOnly,
                                      @Param("unacknowledgedOnly") boolean unacknowledgedOnly);

    @Select("""
            SELECT id, name, CASE status WHEN 0 THEN 'DISABLED' WHEN 1 THEN 'ENABLED'
                 WHEN 2 THEN 'PUBLISHED' ELSE 'UNKNOWN' END AS status,
                 COALESCE(update_time, create_time) AS updated_at,
                 NULL AS engine, NULL AS source_name, NULL AS target_name, 0 AS workspace_runnable
            FROM workflow_def
            WHERE id = #{id} AND project_id = #{projectId} AND deleted = 0
            """)
    TaskDetail workflowTask(@Param("projectId") String projectId, @Param("id") String id);

    @Select("""
            SELECT t.id, t.task_name AS name, CASE t.status WHEN 0 THEN 'DISABLED'
                 WHEN 1 THEN 'ENABLED' ELSE 'UNKNOWN' END AS status,
                 COALESCE(t.update_time, t.create_time) AS updated_at, t.engine,
                 source_ds.ds_name AS source_name, target_ds.ds_name AS target_name,
                 CASE WHEN t.status = 1 AND t.engine = 'DB_SYNC' AND t.write_mode = 'REPLACE_BY_BIZ_DATE'
                   AND COALESCE(TRIM(t.source_query), '') = '' AND COALESCE(TRIM(t.field_mapping), '') = ''
                   AND source_ds.status = 1 AND source_ds.ds_type = 'MYSQL'
                   AND target_ds.status = 1 AND target_ds.ds_type = 'MYSQL'
                   THEN 1 ELSE 0 END AS workspace_runnable
            FROM pipeline_task t
            LEFT JOIN sg_project_group_resource source_binding
                ON source_binding.project_group_id = t.project_group_id
                AND source_binding.resource_type = 'DATASOURCE' AND source_binding.resource_id = t.source_ds_id
            LEFT JOIN meta_datasource source_ds ON source_ds.id = source_binding.resource_id
            LEFT JOIN sg_project_group_resource target_binding
                ON target_binding.project_group_id = t.project_group_id
                AND target_binding.resource_type = 'DATASOURCE' AND target_binding.resource_id = t.target_ds_id
            LEFT JOIN meta_datasource target_ds ON target_ds.id = target_binding.resource_id
            WHERE t.id = #{id} AND t.project_group_id = #{projectId}
            """)
    TaskDetail pipelineTask(@Param("projectId") String projectId, @Param("id") String id);

    @Select("""
            SELECT n.node_code AS code, n.node_name AS name, n.node_type AS type
            FROM workflow_node n JOIN workflow_def w ON w.id = n.wf_id
            WHERE w.id = #{id} AND w.project_id = #{projectId} AND w.deleted = 0 AND n.deleted = 0
            ORDER BY n.create_time, n.id
            """)
    List<ProjectWorkspaceTaskDetailDto.Node> workflowNodes(@Param("projectId") String projectId, @Param("id") String id);

    @Select("""
            SELECT id, wf_id AS task_id, wf_name AS task_name, run_status AS status, biz_date,
                 COALESCE(start_time, create_time) AS started_at, end_time AS ended_at, duration_ms, trigger_type,
                 NULL AS failure_reason, recovery_code, recovery_resolved_at,
                 recovery_resolved_by, recovery_note, alert_acknowledged_at
            FROM workflow_run
            WHERE id = #{id} AND project_group_id = #{projectId}
            """)
    RunDetail workflowRun(@Param("projectId") String projectId, @Param("id") String id);

    @Select("""
            SELECT r.id, r.task_id, t.task_name, CASE r.status WHEN 0 THEN 'RUNNING'
                 WHEN 1 THEN 'SUCCESS' WHEN 2 THEN 'FAILED' WHEN 3 THEN 'RECOVERY_REQUIRED' ELSE 'UNKNOWN' END AS status,
                 r.biz_date, COALESCE(r.start_time, r.create_time) AS started_at,
                 r.end_time AS ended_at, r.duration_ms, r.trigger_type,
                 CASE WHEN r.workspace_request_id IS NOT NULL THEN r.error_message ELSE NULL END AS failure_reason,
                 NULL AS recovery_code, NULL AS recovery_resolved_at, NULL AS recovery_resolved_by,
                 NULL AS recovery_note, NULL AS alert_acknowledged_at
            FROM pipeline_instance r
            LEFT JOIN pipeline_task t ON t.id = r.task_id AND t.project_group_id = r.project_group_id
            WHERE r.id = #{id} AND r.project_group_id = #{projectId}
            """)
    RunDetail pipelineRun(@Param("projectId") String projectId, @Param("id") String id);

    @Select("""
            SELECT n.node_code AS code, n.node_name AS name, n.node_type AS type, n.status, n.duration_ms,
                 n.start_time AS started_at, n.end_time AS ended_at, n.ref_type, n.ref_id
            FROM workflow_node_run n JOIN workflow_run r ON r.id = n.run_id
            WHERE r.id = #{id} AND r.project_group_id = #{projectId}
            ORDER BY n.create_time, n.id
            """)
    List<ProjectWorkspaceRunDetailDto.Node> workflowNodeRuns(@Param("projectId") String projectId,
                                                            @Param("id") String id);

    record TaskDetail(String id, String name, String status, LocalDateTime updatedAt,
                      String engine, String sourceName, String targetName, boolean workspaceRunnable) {}
    record RunDetail(String id, String taskId, String taskName, String status, String bizDate,
                     LocalDateTime startedAt, LocalDateTime endedAt, Long durationMs, String triggerType,
                     String failureReason, String recoveryCode, LocalDateTime recoveryResolvedAt,
                     String recoveryResolvedBy, String recoveryNote, LocalDateTime alertAcknowledgedAt) {}
}
