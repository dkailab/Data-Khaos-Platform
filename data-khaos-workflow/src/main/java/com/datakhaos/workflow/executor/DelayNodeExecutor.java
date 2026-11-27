package com.datakhaos.workflow.executor;

import com.datakhaos.common.exception.BusinessException;
import org.springframework.stereotype.Component;

/**
 * 延时器节点：在 DAG 中插入可控等待，用于错开下游、等待外部系统落数等场景。
 *
 * <p>节点配置（configJson）：{@code {"delaySeconds": 60}}，取值 0~86400 秒。
 * 等待过程按 500ms 分片检查中断，任务取消时能及时退出。</p>
 */
@Component
public class DelayNodeExecutor implements NodeExecutor {

    /** 节点配置键：延时秒数 */
    public static final String CONFIG_DELAY_SECONDS = "delaySeconds";

    private static final int MAX_DELAY_SECONDS = 86_400;

    @Override
    public String getType() {
        return NodeType.DELAY;
    }

    @Override
    public ExecResult execute(NodeContext context) {
        int seconds = parseSeconds(context.getConfig() == null ? null : context.getConfig().get(CONFIG_DELAY_SECONDS));
        long start = System.currentTimeMillis();
        long deadline = start + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("延时等待被中断");
            }
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(500L, Math.max(1L, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("延时等待被中断");
            }
        }
        return ExecResult.builder()
                .success(true)
                .log("延时 " + seconds + " 秒完成，耗时 " + (System.currentTimeMillis() - start) + "ms")
                .build();
    }

    private int parseSeconds(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            throw new BusinessException("延时器未配置 " + CONFIG_DELAY_SECONDS);
        }
        int seconds;
        try {
            seconds = Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new BusinessException("延时秒数必须为整数: " + value);
        }
        if (seconds < 0 || seconds > MAX_DELAY_SECONDS) {
            throw new BusinessException("延时秒数需在 0 至 " + MAX_DELAY_SECONDS + " 之间");
        }
        return seconds;
    }
}
