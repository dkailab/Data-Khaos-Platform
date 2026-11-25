package com.datakhaos.notification.controller;

import cn.hutool.core.util.StrUtil;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.model.R;
import com.datakhaos.common.security.MetadataHolder;
import com.datakhaos.notification.dto.SendRequest;
import com.datakhaos.notification.entity.NotifyRecord;
import com.datakhaos.notification.entity.NotifySubscription;
import com.datakhaos.notification.entity.NotifyTemplate;
import com.datakhaos.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.LocalDateTime;

/**
 * 推送系统接口
 */
@Tag(name = "推送系统")
@RestController
@RequestMapping("/api/notify")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @Operation(summary = "分页查询消息模板")
    @GetMapping("/template/page")
    public R<PageResult<NotifyTemplate>> templatePage(@RequestParam(defaultValue = "1") long current,
                                                      @RequestParam(defaultValue = "10") long size,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) String channel) {
        return R.ok(notificationService.templatePage(current, size, keyword, channel));
    }

    @Operation(summary = "新增消息模板")
    @PostMapping("/template")
    public R<Void> createTemplate(@RequestBody NotifyTemplate template) {
        notificationService.createTemplate(template);
        return R.ok();
    }

    @Operation(summary = "修改消息模板")
    @PutMapping("/template")
    public R<Void> updateTemplate(@RequestBody NotifyTemplate template) {
        notificationService.updateTemplate(template);
        return R.ok();
    }

    @Operation(summary = "删除消息模板")
    @DeleteMapping("/template/{id}")
    public R<Void> deleteTemplate(@PathVariable String id) {
        notificationService.deleteTemplate(id);
        return R.ok();
    }

    @Operation(summary = "发送消息（按模板渲染，支持 SITE/MAIL 渠道）")
    @PostMapping("/send")
    public R<NotifyRecord> send(@RequestBody SendRequest request) {
        return R.ok(notificationService.send(request));
    }

    @Operation(summary = "分页查询推送记录")
    @GetMapping("/record/page")
    public R<PageResult<NotifyRecord>> recordPage(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "10") long size,
                                                  @RequestParam(required = false) String receiverId,
                                                  @RequestParam(required = false) String channel,
                                                  @RequestParam(required = false) Integer status) {
        return R.ok(notificationService.recordPage(current, size, resolveUser(receiverId), channel, status));
    }

    @GetMapping("/inbox")
    public R<PageResult<InboxMessage>> inbox(@RequestParam(defaultValue = "1") long current,
                                             @RequestParam(defaultValue = "10") long size) {
        String receiver = MetadataHolder.getUserId();
        if (StrUtil.isBlank(receiver)) throw new BusinessException(ResultCode.UNAUTHORIZED);
        if (current < 1 || current > Integer.MAX_VALUE || size < 1 || size > 50) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "分页参数无效");
        }
        var page = notificationService.inboxPage(current, size, receiver);
        return R.ok(PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords().stream()
                .map(row -> new InboxMessage(row.getId(), row.getTitle(), row.getContent(), row.getSendTime()))
                .toList()));
    }

    public record InboxMessage(String id, String title, String content, LocalDateTime sendTime) {}

    @Operation(summary = "我的订阅")
    @GetMapping("/subscription/user/{userId}")
    public R<List<NotifySubscription>> subscriptions(@PathVariable String userId) {
        return R.ok(notificationService.subscriptions(resolveUser(userId)));
    }

    @Operation(summary = "新增订阅")
    @PostMapping("/subscription")
    public R<Void> subscribe(@RequestBody NotifySubscription subscription) {
        notificationService.subscribe(subscription);
        return R.ok();
    }

    @Operation(summary = "取消订阅")
    @DeleteMapping("/subscription/{id}")
    public R<Void> unsubscribe(@PathVariable String id) {
        notificationService.unsubscribe(id);
        return R.ok();
    }

    private String resolveUser(String fallback) {
        String uid = MetadataHolder.getUserId();
        return uid != null ? uid : (StrUtil.isBlank(fallback) ? null : fallback);
    }
}
