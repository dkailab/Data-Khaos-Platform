package com.datakhaos.permission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datakhaos.permission.api.model.ProjectWorkspaceResourceDto;
import com.datakhaos.permission.entity.SgProjectGroupResource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SgProjectGroupResourceMapper extends BaseMapper<SgProjectGroupResource> {
    @Select("""
            SELECT r.id, r.resource_type, r.resource_id, d.ds_name,
                   CASE WHEN r.resource_type = 'DATASOURCE'
                        THEN CASE WHEN d.status = 1 THEN 1 ELSE 0 END ELSE NULL END
            FROM sg_project_group_resource r
            LEFT JOIN meta_datasource d ON r.resource_type = 'DATASOURCE' AND r.resource_id = d.id
            WHERE r.project_group_id = #{projectId}
            ORDER BY r.create_time, r.id
            """)
    List<ProjectWorkspaceResourceDto> listWorkspaceResources(@Param("projectId") String projectId);

    @Select("SELECT COUNT(*) FROM meta_datasource WHERE id = #{id} AND status = 1")
    int countAvailableDatasource(@Param("id") String id);
}
