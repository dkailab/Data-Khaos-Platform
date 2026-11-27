package com.datakhaos.schedule.handler;

/** 已受理但无法确认完成：不再重试，避免重复写入。 */
public class NoRetryException extends RuntimeException {
    public NoRetryException(String message) {
        super(message);
    }
}
