package com.datakhaos.workflow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.workflow.entity.WorkflowRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WorkflowRevisionMapper extends BaseMapper<WorkflowRevision> {
    @Select("""
            SELECT revision_number FROM workflow_revision
            WHERE wf_id = #{wfId} ORDER BY revision_number DESC LIMIT 1
            """)
    Integer latestNumber(@Param("wfId") String wfId);

    @Select("""
            SELECT graph_json FROM workflow_revision
            WHERE wf_id = #{wfId} AND revision_number = #{revisionNumber} LIMIT 1
            """)
    String graphJson(@Param("wfId") String wfId, @Param("revisionNumber") Integer revisionNumber);
}
