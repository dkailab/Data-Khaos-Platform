package com.datakhaos.approval.controller;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.approval.api.model.ApprovalEvidenceDto;
import com.datakhaos.approval.dto.ApplyRequest;
import com.datakhaos.approval.dto.ApprovalActionRequest;
import com.datakhaos.approval.dto.TransferRequest;
import com.datakhaos.approval.entity.AppApply;
import com.datakhaos.approval.entity.AppApprovalFlow;
import com.datakhaos.approval.service.ApprovalService;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.R;
import com.datakhaos.common.security.MetadataHolder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 权限审批接口
 */
@Tag(name = "权限审批")
@RestController
@RequestMapping("/api/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @Operation(summary = "提交权限申请")
    @PostMapping("/apply")
    public R<AppApply> submit(@RequestBody ApplyRequest request,
                              @RequestParam(required = false) String applicantId) {
        return R.ok(approvalService.submit(request, currentUser(applicantId)));
    }

    @Operation(summary = "我的申请")
    @GetMapping("/apply/page")
    public R<PageResult<AppApply>> my(@RequestParam(defaultValue = "1") long current,
                                      @RequestParam(defaultValue = "10") long size,
                                      @RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) String userId) {
        return R.ok(approvalService.pageMy(current, size, currentUser(userId), status));
    }

    @Operation(summary = "待审批列表")
    @GetMapping("/apply/pending")
    public R<PageResult<AppApply>> pending(@RequestParam(defaultValue = "1") long current,
                                           @RequestParam(defaultValue = "10") long size) {
        return R.ok(approvalService.pagePending(current, size));
    }

    @Operation(summary = "全部申请（审批管理）")
    @GetMapping("/apply/all")
    public R<PageResult<AppApply>> all(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) Integer status) {
        return R.ok(approvalService.pageAll(current, size, keyword, status));
    }

    @Operation(summary = "申请详情（含审批记录）")
    @GetMapping("/apply/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(approvalService.detail(id));
    }

    @Operation(summary = "通过（自动授权）")
    @PostMapping("/apply/{id}/approve")
    public R<Void> approve(@PathVariable String id, @RequestBody(required = false) ApprovalActionRequest request,
                           @RequestParam(required = false) String approverId) {
        approvalService.approve(id, currentUser(resolveApprover(request, approverId)), request == null ? null : request.getComment());
        return R.ok();
    }

    @Operation(summary = "驳回")
    @PostMapping("/apply/{id}/reject")
    public R<Void> reject(@PathVariable String id, @RequestBody(required = false) ApprovalActionRequest request,
                          @RequestParam(required = false) String approverId) {
        approvalService.reject(id, currentUser(resolveApprover(request, approverId)), request == null ? null : request.getComment());
        return R.ok();
    }

    @Operation(summary = "转交")
    @PostMapping("/apply/{id}/transfer")
    public R<Void> transfer(@PathVariable String id, @RequestBody TransferRequest request,
                            @RequestParam(required = false) String approverId) {
        approvalService.transfer(id, currentUser(approverId), request.getToApproverId(), request.getComment());
        return R.ok();
    }

    @Operation(summary = "撤销申请")
    @PostMapping("/apply/{id}/cancel")
    public R<Void> cancel(@PathVariable String id, @RequestParam(required = false) String applicantId) {
        approvalService.cancel(id, currentUser(applicantId));
        return R.ok();
    }

    @Operation(summary = "审批流程定义列表")
    @GetMapping("/flow/list")
    public R<List<AppApprovalFlow>> flows() {
        return R.ok(approvalService.flows());
    }

    /** 按目标版本查询已通过的审批；查不到返回 null，调用方必须按「未批准」处理。 */
    @Operation(summary = "查询目标版本已通过的审批")
    @GetMapping("/apply/approved")
    public R<ApprovalEvidenceDto> approved(@RequestParam String applyType,
                                           @RequestParam String targetId,
                                           @RequestParam(required = false) Integer targetRevision) {
        return R.ok(approvalService.findApproved(applyType, targetId, targetRevision));
    }

    /** 优先取请求上下文用户；直接调用时回退到参数 */
    private String currentUser(String fallback) {
        String uid = MetadataHolder.getUserId();
        return uid != null ? uid : (StrUtil.isBlank(fallback) ? "1" : fallback);
    }

    private String resolveApprover(ApprovalActionRequest request, String approverId) {
        if (request != null && StrUtil.isNotBlank(request.getApproverId())) {
            return request.getApproverId();
        }
        return approverId;
    }
}
