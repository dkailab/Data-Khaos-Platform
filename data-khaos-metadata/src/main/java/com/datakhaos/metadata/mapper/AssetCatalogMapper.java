package com.datakhaos.metadata.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.metadata.dto.AssetTable;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AssetCatalogMapper {
    String FIELDS = """
            SELECT t.id, d.datasource_id, d.database_name, t.table_name, t.table_type, t.description, t.sync_time
            FROM meta_table t JOIN meta_database d ON d.id = t.database_id
            """;

    String SCOPE = """
            <if test="!admin">
                AND
                <choose>
                    <when test="grants.size() > 0">
                        <foreach collection="grants" item="grant" open="(" separator=" OR " close=")">
                            (1 = 1
                            <if test="grant.datasourceId != null and grant.datasourceId != ''">
                                AND d.datasource_id = #{grant.datasourceId}
                            </if>
                            <if test="grant.databaseName != null and grant.databaseName != ''">
                                AND d.database_name = #{grant.databaseName}
                            </if>
                            <if test="grant.tableName != null and grant.tableName != ''">
                                AND t.table_name = #{grant.tableName}
                            </if>
                            )
                        </foreach>
                    </when>
                    <otherwise>1 = 0</otherwise>
                </choose>
            </if>
            """;

    @Select("<script>" + FIELDS + " WHERE 1 = 1 " + SCOPE + """
            <if test="keyword != null">
                AND (LOWER(t.table_name) LIKE #{keyword} OR LOWER(t.description) LIKE #{keyword}
                    OR EXISTS (SELECT 1 FROM meta_column c WHERE c.table_id = t.id
                        AND (LOWER(c.column_name) LIKE #{keyword} OR LOWER(c.biz_name) LIKE #{keyword}
                          OR LOWER(c.description) LIKE #{keyword})))
            </if>
            ORDER BY d.datasource_id, d.database_name, t.table_name, t.id
            </script>
            """)
    IPage<AssetTable> catalog(Page<AssetTable> page, @Param("keyword") String keyword,
                             @Param("admin") boolean admin, @Param("grants") List<Map<String, Object>> grants);

    @Select("<script>" + FIELDS + """
            WHERE t.id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            """ + SCOPE + "</script>")
    List<AssetTable> visibleTables(@Param("ids") List<String> ids, @Param("admin") boolean admin,
                                   @Param("grants") List<Map<String, Object>> grants);
}
