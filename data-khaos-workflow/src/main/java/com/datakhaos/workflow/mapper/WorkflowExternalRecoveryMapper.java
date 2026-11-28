package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.WorkflowExternalRecovery;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkflowExternalRecoveryMapper extends BaseMapper<WorkflowExternalRecovery> {

    @Select("""
            SELECT * FROM workflow_external_recovery
            WHERE node_run_id = #{nodeRunId} AND action = #{action} LIMIT 1
            """)
    WorkflowExternalRecovery find(@Param("nodeRunId") String nodeRunId, @Param("action") String action);

    /** 待轮询的恢复请求；update_time 早于 cutoff 避免刚提交的请求被重复读取。 */
    @Select("""
            SELECT * FROM workflow_external_recovery
            WHERE status IN ('ACCEPTED', 'POLLING') AND update_time < #{cutoff}
            ORDER BY update_time, id LIMIT 50
            """)
    List<WorkflowExternalRecovery> pollable(@Param("cutoff") LocalDateTime cutoff);
}
