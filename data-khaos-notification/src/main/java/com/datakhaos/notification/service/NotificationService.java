package com.datakhaos.notification.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.PageResult;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.notification.dto.SendRequest;
import com.datakhaos.notification.entity.NotifyRecord;
import com.datakhaos.notification.entity.NotifySubscription;
import com.datakhaos.notification.entity.NotifyTemplate;
import com.datakhaos.notification.mapper.NotifyRecordMapper;
import com.datakhaos.notification.mapper.NotifySubscriptionMapper;
import com.datakhaos.notification.mapper.NotifyTemplateMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 推送服务：模板管理、多渠道发送、推送记录与用户订阅。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotifyTemplateMapper templateMapper;
    private final NotifyRecordMapper recordMapper;
    private final NotifySubscriptionMapper subscriptionMapper;
    private final ObjectProvider<JavaMailSender> mailSenderProvider;

    // ==================== 模板 ====================

    public PageResult<NotifyTemplate> templatePage(long current, long size, String keyword, String channel) {
        Page<NotifyTemplate> page = templateMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<NotifyTemplate>()
                        .like(StrUtil.isNotBlank(keyword), NotifyTemplate::getTemplateName, keyword)
                        .eq(StrUtil.isNotBlank(channel), NotifyTemplate::getChannel, channel)
                        .orderByDesc(NotifyTemplate::getCreateTime));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    @Transactional(rollbackFor = Exception.class)
    public void createTemplate(NotifyTemplate template) {
        if (StrUtil.isBlank(template.getTemplateCode()) || StrUtil.isBlank(template.getTemplateName())) {
            throw new BusinessException("模板编码与名称不能为空");
        }
        if (templateMapper.selectCount(new LambdaQueryWrapper<NotifyTemplate>()
                .eq(NotifyTemplate::getTemplateCode, template.getTemplateCode())) > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "模板编码已存在: " + template.getTemplateCode());
        }
        template.setStatus(template.getStatus() == null ? 1 : template.getStatus());
        templateMapper.insert(template);
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(NotifyTemplate template) {
        if (StrUtil.isBlank(template.getId())) {
            throw new BusinessException("模板ID不能为空");
        }
        templateMapper.updateById(template);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(String id) {
        templateMapper.deleteById(id);
    }

    // ==================== 发送 ====================

    /** 渲染模板并发送（返回推送记录） */
    public NotifyRecord send(SendRequest request) {
        if (StrUtil.isBlank(request.getTemplateCode()) || StrUtil.isBlank(request.getReceiverId())) {
            throw new BusinessException("模板编码与接收人不能为空");
        }
        NotifyTemplate template = getByCode(request.getTemplateCode());
        String channel = StrUtil.isBlank(request.getChannel()) ? template.getChannel() : request.getChannel();
        Map<String, Object> vars = request.getVars() == null ? Map.of() : request.getVars();

        NotifyRecord record = new NotifyRecord();
        record.setTemplateId(template.getId());
        record.setReceiverId(request.getReceiverId());
        record.setReceiverType(StrUtil.isBlank(request.getReceiverType()) ? "USER" : request.getReceiverType());
        record.setChannel(channel);
        record.setTitle(render(template.getTitleTemplate(), vars));
        record.setContent(render(template.getContentTemplate(), vars));
        record.setStatus(0);
        recordMapper.insert(record);

        deliver(record);
        return record;
    }

    /** 站内通知与调用方的业务记录在同一事务提交。 */
    public void recordSiteMessage(String receiverId, String title, String content) {
        recordMapper.insert(siteRecord(receiverId, title, content));
    }

    public void recordFlinkAlertEvent(FlinkAlertEvent event) {
        String title = switch (event.type()) {
            case OPEN -> "Flink 检查点连续失败";
            case RECOVERED -> "Flink 检查点已恢复";
            case PRESSURE_OPEN -> "Flink 作业持续反压";
            case PRESSURE_CLEAR -> "Flink 作业反压已恢复";
        };
        String detail = switch (event.type()) {
            case OPEN -> " 的检查点巡检已触发告警。";
            case RECOVERED -> " 已产生新的成功检查点。";
            case PRESSURE_OPEN -> " 的执行节点反压持续偏高，请检查下游处理速度。";
            case PRESSURE_CLEAR -> " 的执行节点反压已回落。";
        };
        String content = "运行 " + event.runId() + detail + " 告警 ID " + event.alertId() + "。";
        var record = siteRecord(event.receiverId(), title, content);
        record.setId("f-" + DigestUtil.sha256Hex(event.alertId() + ":" + event.type().name()).substring(0, 30));
        try {
            recordMapper.insert(record);
        } catch (DuplicateKeyException duplicate) {
            var saved = recordMapper.selectById(record.getId());
            if (saved == null || !"SITE".equals(saved.getChannel())
                    || !Objects.equals(saved.getReceiverId(), record.getReceiverId())
                    || !Objects.equals(saved.getTitle(), record.getTitle())
                    || !Objects.equals(saved.getContent(), record.getContent())) {
                throw duplicate;
            }
        }
    }

    private NotifyRecord siteRecord(String receiverId, String title, String content) {
        NotifyRecord record = new NotifyRecord();
        record.setReceiverId(receiverId);
        record.setReceiverType("USER");
        record.setChannel("SITE");
        record.setTitle(title);
        record.setContent(content);
        record.setStatus(1);
        record.setSendTime(LocalDateTime.now());
        return record;
    }

    /** 按渠道投递并更新记录状态 */
    private void deliver(NotifyRecord record) {
        try {
            String channel = record.getChannel() == null ? "" : record.getChannel().toUpperCase();
            switch (channel) {
                case "SITE" -> {
                    // 站内信：入库即送达
                    record.setStatus(1);
                    record.setSendTime(LocalDateTime.now());
                }
                case "MAIL" -> sendMail(record);
                default -> {
                    record.setStatus(2);
                    record.setErrorMessage("渠道未接入: " + record.getChannel());
                }
            }
        } catch (Exception e) {
            record.setStatus(2);
            record.setErrorMessage(StrUtil.maxLength(e.getMessage(), 500));
            log.warn("推送失败 recordId={}: {}", record.getId(), e.getMessage());
        }
        recordMapper.updateById(record);
    }

    private void sendMail(NotifyRecord record) {
        JavaMailSender sender = mailSenderProvider.getIfAvailable();
        if (sender == null) {
            throw new BusinessException("邮件服务未配置（spring.mail.host）");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo(record.getReceiverId());
        message.setSubject(record.getTitle());
        message.setText(record.getContent());
        sender.send(message);
        record.setStatus(1);
        record.setSendTime(LocalDateTime.now());
    }

    // ==================== 记录 ====================

    public PageResult<NotifyRecord> recordPage(long current, long size, String receiverId, String channel, Integer status) {
        return recordPage(current, size, receiverId, channel, status, null);
    }

    public PageResult<NotifyRecord> inboxPage(long current, long size, String receiverId) {
        return recordPage(current, size, receiverId, "SITE", 1, "USER");
    }

    private PageResult<NotifyRecord> recordPage(long current, long size, String receiverId, String channel,
                                                 Integer status, String receiverType) {
        Page<NotifyRecord> page = recordMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<NotifyRecord>()
                        .eq(StrUtil.isNotBlank(receiverId), NotifyRecord::getReceiverId, receiverId)
                        .eq(StrUtil.isNotBlank(channel), NotifyRecord::getChannel, channel)
                        .eq(receiverType != null, NotifyRecord::getReceiverType, receiverType)
                        .eq(status != null, NotifyRecord::getStatus, status)
                        .orderByDesc(NotifyRecord::getCreateTime, NotifyRecord::getId));
        return PageResult.of(page.getCurrent(), page.getSize(), page.getTotal(), page.getRecords());
    }

    // ==================== 订阅 ====================

    public List<NotifySubscription> subscriptions(String userId) {
        return subscriptionMapper.selectList(new LambdaQueryWrapper<NotifySubscription>()
                .eq(NotifySubscription::getUserId, userId)
                .orderByDesc(NotifySubscription::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public void subscribe(NotifySubscription subscription) {
        if (StrUtil.isBlank(subscription.getUserId()) || StrUtil.isBlank(subscription.getSubscribeType())) {
            throw new BusinessException("用户与订阅类型不能为空");
        }
        long count = subscriptionMapper.selectCount(new LambdaQueryWrapper<NotifySubscription>()
                .eq(NotifySubscription::getUserId, subscription.getUserId())
                .eq(NotifySubscription::getSubscribeType, subscription.getSubscribeType())
                .eq(subscription.getTargetId() != null, NotifySubscription::getTargetId, subscription.getTargetId()));
        if (count > 0) {
            throw new BusinessException(ResultCode.DUPLICATE_KEY, "已订阅，请勿重复订阅");
        }
        subscription.setChannel(StrUtil.isBlank(subscription.getChannel()) ? "SITE" : subscription.getChannel());
        subscription.setStatus(subscription.getStatus() == null ? 1 : subscription.getStatus());
        subscriptionMapper.insert(subscription);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unsubscribe(String id) {
        subscriptionMapper.deleteById(id);
    }

    // ==================== 私有方法 ====================

    private NotifyTemplate getByCode(String templateCode) {
        NotifyTemplate template = templateMapper.selectOne(new LambdaQueryWrapper<NotifyTemplate>()
                .eq(NotifyTemplate::getTemplateCode, templateCode));
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "模板不存在: " + templateCode);
        }
        return template;
    }

    /** ${var} 占位符渲染 */
    private String render(String template, Map<String, Object> vars) {
        if (template == null) {
            return null;
        }
        String result = template;
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return result;
    }
}
