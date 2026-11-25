package com.datakhaos.query.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.datakhaos.query.entity.WarehouseApiQuotaWindow;
import com.datakhaos.query.mapper.WarehouseApiQuotaWindowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按 {@code appId + apiVersionId} 的分钟固定窗口配额。
 *
 * <p>计数落在共享库的行上而非进程内存，因此同一应用的多实例调用天然共享同一份额度；
 * 递增走带条件的原子 UPDATE，并发下不会超发。窗口按自然分钟推进，跨窗口自动归零。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WarehouseApiQuotaService {
    private static final long WINDOW_MS = 60_000L;
    /** 保留 5 个窗口，够跨窗口边界复核，又不至于无限堆积。 */
    private static final long RETAIN_WINDOWS = 5;
    private static final int CLEANUP_INTERVAL = 512;

    private final WarehouseApiQuotaWindowMapper mapper;
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * 尝试占用一次配额。
     *
     * @return true 表示放行，false 表示本窗口额度已用尽
     */
    public boolean tryAcquire(String appId, String apiVersionId, int quotaPerMinute) {
        if (quotaPerMinute < 1) return false;
        long window = System.currentTimeMillis() / WINDOW_MS;
        cleanupOccasionally(window);
        // 先试递增，再试建行；建行撞唯一键说明别的实例刚建好，回到递增重试一次。
        for (int attempt = 0; attempt < 2; attempt++) {
            if (mapper.update(null, Wrappers.<WarehouseApiQuotaWindow>lambdaUpdate()
                    .eq(WarehouseApiQuotaWindow::getAppId, appId)
                    .eq(WarehouseApiQuotaWindow::getApiVersionId, apiVersionId)
                    .eq(WarehouseApiQuotaWindow::getWindowStart, window)
                    .lt(WarehouseApiQuotaWindow::getUsedCount, quotaPerMinute)
                    .setSql("used_count = used_count + 1")) == 1) {
                return true;
            }
            if (insertWindow(appId, apiVersionId, window)) return true;
        }
        return false;
    }

    /** 供验收与排障读取当前窗口已用量。 */
    public int used(String appId, String apiVersionId) {
        long window = System.currentTimeMillis() / WINDOW_MS;
        var row = mapper.selectOne(Wrappers.<WarehouseApiQuotaWindow>lambdaQuery()
                .eq(WarehouseApiQuotaWindow::getAppId, appId)
                .eq(WarehouseApiQuotaWindow::getApiVersionId, apiVersionId)
                .eq(WarehouseApiQuotaWindow::getWindowStart, window));
        return row == null ? 0 : row.getUsedCount();
    }

    private boolean insertWindow(String appId, String apiVersionId, long window) {
        var row = new WarehouseApiQuotaWindow();
        row.setId(appId + ":" + apiVersionId + "@" + window);
        row.setAppId(appId);
        row.setApiVersionId(apiVersionId);
        row.setWindowStart(window);
        row.setUsedCount(1);
        try {
            mapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    private void cleanupOccasionally(long window) {
        if (calls.incrementAndGet() % CLEANUP_INTERVAL != 0) return;
        try {
            mapper.delete(Wrappers.<WarehouseApiQuotaWindow>lambdaQuery()
                    .lt(WarehouseApiQuotaWindow::getWindowStart, window - RETAIN_WINDOWS));
        } catch (RuntimeException e) {
            log.warn("清理配额窗口失败: {}", e.getMessage());
        }
    }
}
