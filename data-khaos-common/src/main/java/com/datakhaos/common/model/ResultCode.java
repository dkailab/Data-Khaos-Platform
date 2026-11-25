package com.datakhaos.common.model;

import lombok.Getter;

/**
 * 业务状态码
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "操作成功"),
    FAILED(500, "操作失败"),
    VALIDATE_FAILED(400, "参数校验失败"),
    UNAUTHORIZED(401, "暂未登录或登录已过期"),
    FORBIDDEN(403, "没有相关权限"),
    NOT_FOUND(404, "资源不存在"),
    TOO_MANY_REQUESTS(429, "请求超出调用配额"),
    SYSTEM_ERROR(5000, "系统异常"),
    BUSINESS_ERROR(5001, "业务异常"),
    DUPLICATE_KEY(5002, "数据已存在"),
    TOKEN_INVALID(4010, "令牌无效"),
    TOKEN_EXPIRED(4011, "令牌已过期");

    private final int code;
    private final String msg;

    ResultCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
