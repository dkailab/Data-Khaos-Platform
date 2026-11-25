package com.datakhaos.query.exception;

import com.datakhaos.common.exception.BusinessException;
import com.datakhaos.common.model.ResultCode;

/**
 * 调用超出配额：调用方应收到 HTTP 429，而非与其他业务异常一样统一 200。
 */
public class QuotaExceededException extends BusinessException {
    private static final long serialVersionUID = 1L;

    /** 距离下一个配额窗口还有多少秒，供 Retry-After 提示。 */
    private final int retryAfterSeconds;

    public QuotaExceededException(String message, int retryAfterSeconds) {
        super(ResultCode.TOO_MANY_REQUESTS, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
