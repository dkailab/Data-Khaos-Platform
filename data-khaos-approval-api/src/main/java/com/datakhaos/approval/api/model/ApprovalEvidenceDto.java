package com.datakhaos.approval.api.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 已通过的审批证据：调用方据此判断「这个目标版本是否被批准过」。
 *
 * <p>只返回结论与审计信息，不暴露审批意见之外的业务数据。查不到时返回 null，
 * 调用方不得把「查不到」当成「已批准」。</p>
 *
 * @param applyId      申请单 ID
 * @param applyType    申请类型，如 WAREHOUSE_API / FLINK_JOB
 * @param targetId     目标 ID（API 版本 ID / 作业资产 ID）
 * @param targetRevision 目标修订号；为空表示不区分版本（历史数据）
 * @param applicantId  申请人
 * @param approverId   最终审批人
 * @param approvedAt   审批通过时间
 * @param comment      审批意见
 */
public record ApprovalEvidenceDto(String applyId, String applyType, String targetId, Integer targetRevision,
                                  String applicantId, String approverId, LocalDateTime approvedAt,
                                  String comment) implements Serializable {
}
