package com.datakhaos.common.security;

import com.datakhaos.common.constant.CommonConstants;
import lombok.extern.slf4j.Slf4j;

/**
 * 请求级用户上下文（ThreadLocal）。
 * 由网关写入请求头，各服务通过 {@link MetadataContextFilter} 解析后存入。
 */
@Slf4j
public class MetadataHolder {

    private static final ThreadLocal<AuthUser> CONTEXT = new ThreadLocal<>();

    public static void set(AuthUser user) {
        CONTEXT.set(user);
    }

    public static AuthUser get() {
        return CONTEXT.get();
    }

    public static String getUserId() {
        AuthUser user = CONTEXT.get();
        return user == null ? null : user.getId();
    }

    public static String getUsername() {
        AuthUser user = CONTEXT.get();
        return user == null ? null : user.getUsername();
    }

    public static boolean isSuperAdmin() {
        AuthUser user = CONTEXT.get();
        if (user == null) {
            return false;
        }
        return CommonConstants.DEFAULT_ADMIN_ID.equals(user.getId())
                || (user.getRoles() != null && user.getRoles().contains(CommonConstants.SUPER_ADMIN));
    }

    /** 当前请求来自内部编排客户端。服务端口应仅供受信网络访问。 */
    public static boolean isSystemCaller() {
        return CommonConstants.DEFAULT_ADMIN_ID.equals(getUserId()) && "system".equals(getUsername());
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
