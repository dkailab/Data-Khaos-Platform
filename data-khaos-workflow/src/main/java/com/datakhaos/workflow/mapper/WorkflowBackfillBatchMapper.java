package com.datakhaos.workflow.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface WorkflowBackfillBatchMapper {

    @Insert("""
            INSERT INTO workflow_backfill_batch (wf_id, request_id, from_date, to_date, request_hash)
            VALUES (#{wfId}, #{requestId}, #{fromDate}, #{toDate}, #{requestHash})
            ON DUPLICATE KEY UPDATE request_hash = IF(legacy_conflict = 0
                AND from_date = VALUES(from_date) AND to_date = VALUES(to_date),
                COALESCE(request_hash, VALUES(request_hash)), request_hash)
            """)
    void reserve(@Param("wfId") String wfId, @Param("requestId") String requestId,
                 @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                 @Param("requestHash") String requestHash);

    @Select("""
            SELECT COUNT(*) FROM workflow_backfill_batch
            WHERE wf_id = #{wfId} AND request_id = #{requestId}
              AND from_date = #{fromDate} AND to_date = #{toDate}
              AND request_hash = #{requestHash} AND legacy_conflict = 0
            """)
    boolean matches(@Param("wfId") String wfId, @Param("requestId") String requestId,
                    @Param("fromDate") LocalDate fromDate, @Param("toDate") LocalDate toDate,
                    @Param("requestHash") String requestHash);
}
