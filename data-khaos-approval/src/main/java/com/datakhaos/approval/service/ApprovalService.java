package com.datakhaos.approval.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.approval.api.model.ApprovalEvidenceDto;
import com.datakhaos.approval.dto.ApplyRequest;
import com.datakhaos.approval.entity.AppApply;
import com.datakhaos.approval.entity.AppApprovalFlow;
import com.datakhaos.approval.entity.AppApprovalRecord;
import com.datakhaos.approval.mapper.AppApplyMapper;
import com.datakhaos.approval.mapper.AppApprovalFlowMapper;
import com.datakhaos.approval.mapper.AppApprovalRecordMapper;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.permission.api.service.PermissionApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批流服务：权限申请、审批流转、自动授权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final AppApplyMapper applyMapper;
    private final AppApprovalRecordMapper recordMapper;
    private final AppApprovalFlowMapper flowMapper;
    private final PermissionApiClient permissionApiClient;

    // ---------- 申请 ----------

    /** 提交权限申请（默认待审批） */
    @Transactional(rollbackFor = Exception.class)
    public AppApply submit(ApplyRequest request, String applicantId) {
        if (StrUtil.isBlank(request.getApplyType())) {
            throw new BusinessException("申请类型不能为空");
        }
        if (StrUtil.isBlank(request.getTargetId())) {
            throw new BusinessException("申请目标不能为空");
        }
        AppApply apply = new AppApply();
        apply.setApplicantId(applicantId);
        apply.setApplyType(request.getApplyType().toUpperCase());
        apply.setTargetId(request.getTargetId());
        apply.setTargetRevision(request.getTargetRevision());
        apply.setTargetName(request.getTargetName());
        apply.setReason(request.getReason());
        apply.setStatus(0);
        applyMapper.insert(apply);
        return apply;
    }

    /** 我的申请 */
    public PageResult<AppApply> pageMy(long current, long size, String userId, Integer status) {
        Page<AppApply> page = applyMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AppApply>()
                        .eq(AppApply::getApplicantId, userId)
                        .eq(status != null, AppApply::getStatus, status)
                        .orderByDesc(AppApply::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 待审批列表 */
    public PageResult<AppApply> pagePending(long current, long size) {
        Page<AppApply> page = applyMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AppApply>()
                        .eq(AppApply::getStatus, 0)
                        .orderByAsc(AppApply::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 全部申请（审批管理视图） */
    public PageResult<AppApply> pageAll(long current, long size, String keyword, Integer status) {
        Page<AppApply> page = applyMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<AppApply>()
                        .and(StrUtil.isNotBlank(keyword), w -> w
                                .like(AppApply::getTargetName, keyword)
                                .or().like(AppApply::getApplicantId, keyword))
                        .eq(status != null, AppApply::getStatus, status)
                        .orderByDesc(AppApply::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    /** 申请详情（含审批记录） */
    public Map<String, Object> detail(String id) {
        AppApply apply = getApply(id);
        List<AppApprovalRecord> records = recordMapper.selectList(
                new LambdaQueryWrapper<AppApprovalRecord>()
                        .eq(AppApprovalRecord::getApplyId, id)
                        .orderByAsc(AppApprovalRecord::getCreateTime));
        Map<String, Object> result = new HashMap<>();
        result.put("apply", apply);
        result.put("records", records);
        return result;
    }

    // ---------- 审批 ----------

    /** 通过：写审批记录，状态置为通过，自动授权 */
    @Transactional(rollbackFor = Exception.class)
    public void approve(String id, String approverId, String comment) {
        AppApply apply = getApply(id);
        checkPending(apply);
        addRecord(id, approverId, 1, comment);
        apply.setStatus(1);
        apply.setCurrentApprover(null);
        applyMapper.updateById(apply);
        autoGrant(apply);
    }

    /** 驳回 */
    @Transactional(rollbackFor = Exception.class)
    public void reject(String id, String approverId, String comment) {
        AppApply apply = getApply(id);
        checkPending(apply);
        addRecord(id, approverId, 2, comment);
        apply.setStatus(2);
        apply.setCurrentApprover(null);
        applyMapper.updateById(apply);
    }

    /** 转交 */
    @Transactional(rollbackFor = Exception.class)
    public void transfer(String id, String approverId, String toApproverId, String comment) {
        if (StrUtil.isBlank(toApproverId)) {
            throw new BusinessException("转交目标审批人不能为空");
        }
        AppApply apply = getApply(id);
        checkPending(apply);
        addRecord(id, approverId, 3, comment);
        apply.setCurrentApprover(toApproverId);
        applyMapper.updateById(apply);
    }

    /** 申请人撤销 */
    @Transactional(rollbackFor = Exception.class)
    public void cancel(String id, String applicantId) {
        AppApply apply = getApply(id);
        if (apply.getStatus() != 0) {
            throw new BusinessException("仅待审批状态可撤销");
        }
        if (applicantId != null && !applicantId.equals(apply.getApplicantId())) {
            throw new BusinessException("仅申请人可撤销");
        }
        apply.setStatus(3);
        applyMapper.updateById(apply);
    }

    // ---------- 流程定义 ----------

    public List<AppApprovalFlow> flows() {
        return flowMapper.selectList(new LambdaQueryWrapper<AppApprovalFlow>()
                .orderByAsc(AppApprovalFlow::getApplyType)
                .orderByAsc(AppApprovalFlow::getStepOrder));
    }

    /**
     * 查询目标版本最近一次通过的审批。
     *
     * <p>只认 {@code status=1} 且 {@code target_revision} 精确相等的申请：
     * 审批后再次编辑会推进修订号，旧审批自然失效。查不到返回 null。</p>
     */
    public ApprovalEvidenceDto findApproved(String applyType, String targetId, Integer targetRevision) {
        if (StrUtil.isBlank(applyType) || StrUtil.isBlank(targetId) || targetRevision == null) {
            return null;
        }
        AppApply apply = applyMapper.selectOne(new LambdaQueryWrapper<AppApply>()
                .eq(AppApply::getApplyType, applyType.strip().toUpperCase())
                .eq(AppApply::getTargetId, targetId.strip())
                .eq(AppApply::getTargetRevision, targetRevision)
                .eq(AppApply::getStatus, 1)
                .orderByDesc(AppApply::getUpdateTime)
                .last("LIMIT 1"));
        if (apply == null) {
            return null;
        }
        AppApprovalRecord record = recordMapper.selectOne(new LambdaQueryWrapper<AppApprovalRecord>()
                .eq(AppApprovalRecord::getApplyId, apply.getId())
                .eq(AppApprovalRecord::getAction, 1)
                .orderByDesc(AppApprovalRecord::getCreateTime)
                .last("LIMIT 1"));
        return new ApprovalEvidenceDto(apply.getId(), apply.getApplyType(), apply.getTargetId(),
                apply.getTargetRevision(), apply.getApplicantId(),
                record == null ? apply.getCurrentApprover() : record.getApproverId(),
                record == null ? null : record.getCreateTime(),
                record == null ? null : record.getComment());
    }

    // ---------- 私有方法 ----------

    /** 自动授权：表权限申请通过后，给申请人授 SELECT 权限 */
    private void autoGrant(AppApply apply) {
        if (!"TABLE".equalsIgnoreCase(apply.getApplyType())) {
            log.info("申请类型 {} 暂不做自动授权", apply.getApplyType());
            return;
        }
        if (StrUtil.isBlank(apply.getTargetName())) {
            log.warn("申请单 {} 缺少目标表信息，跳过自动授权", apply.getId());
            return;
        }
        String[] parts = apply.getTargetName().split("\\.", 2);
        if (parts.length < 2) {
            log.warn("申请单 {} 目标表格式应为 database.table: {}", apply.getId(), apply.getTargetName());
            return;
        }
        Map<String, Object> permission = new HashMap<>();
        permission.put("datasourceId", apply.getTargetId());
        permission.put("databaseName", parts[0]);
        permission.put("tableName", parts[1]);
        permission.put("permissionType", "SELECT");
        permission.put("userId", apply.getApplicantId());
        permission.put("grantType", "USER");
        boolean ok = permissionApiClient.grantTablePermission(permission);
        log.info("自动授权结果 applyId={}, success={}", apply.getId(), ok);
    }

    private void addRecord(String applyId, String approverId, int action, String comment) {
        AppApprovalRecord record = new AppApprovalRecord();
        record.setApplyId(applyId);
        record.setApproverId(approverId);
        record.setAction(action);
        record.setComment(comment);
        recordMapper.insert(record);
    }

    private AppApply getApply(String id) {
        AppApply apply = applyMapper.selectById(id);
        if (apply == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "申请单不存在: " + id);
        }
        return apply;
    }

    private void checkPending(AppApply apply) {
        if (apply.getStatus() == null || apply.getStatus() != 0) {
            throw new BusinessException("该申请单当前不可审批");
        }
    }
}
