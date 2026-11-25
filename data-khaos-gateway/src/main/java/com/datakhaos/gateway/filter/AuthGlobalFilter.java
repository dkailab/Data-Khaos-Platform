package com.datakhaos.gateway.filter;

import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.model.R;
import com.datakhaos.common.model.ResultCode;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.common.security.WarehouseApiToken;
import com.datakhaos.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.List;

/**
 * 网关全局过滤器：
 * <ol>
 *     <li>白名单放行（登录、验证码、接口文档等）；</li>
 *     <li>校验 JWT，失败返回 401；</li>
 *     <li>透传用户身份头 {@code X-User-Id / X-Username} 给下游服务。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/captcha",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/favicon.ico");

    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        log.debug("网关请求: {} {}", request.getMethod(), path);

        if (isWhiteList(path)) {
            return chain.filter(exchange);
        }

        String authorization = request.getHeaders().getFirst(CommonConstants.AUTH_HEADER);
        if (WarehouseApiToken.isMachineAuthorization(authorization)) {
            if (HttpMethod.GET.equals(request.getMethod()) && MATCHER.match(WarehouseApiToken.DATA_ROUTE, path)) {
                // The query service validates the credential. Supply no platform identity for this request.
                return forward(exchange, chain, null, null);
            }
            return unauthorized(exchange, "调用凭据不适用于此接口");
        }
        String token = resolveToken(authorization);
        if (token == null || !JwtUtil.verify(token, properties.getSecret())) {
            return unauthorized(exchange, "登录已过期或未登录");
        }

        String uid = JwtUtil.getUid(token, properties.getSecret());
        Date expiry = JwtUtil.getExpiresAt(token);
        if (uid == null || uid.isBlank() || expiry == null || !expiry.after(new Date())) {
            return unauthorized(exchange, "登录已过期或未登录");
        }
        String username = JwtUtil.getUsername(token, properties.getSecret());
        return forward(exchange, chain, uid, username);
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, String uid, String username) {
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(CommonConstants.HEADER_USER_ID);
                    headers.remove(CommonConstants.HEADER_USERNAME);
                    if (uid != null) headers.set(CommonConstants.HEADER_USER_ID, uid);
                    if (username != null) headers.set(CommonConstants.HEADER_USERNAME, username);
                    headers.remove(CommonConstants.HEADER_ROLES);
                    headers.remove(CommonConstants.HEADER_PERMISSIONS);
                    headers.remove(CommonConstants.HEADER_REAL_NAME);
                })
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean isWhiteList(String path) {
        return WHITE_LIST.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }

    private String resolveToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        return authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(R.fail(ResultCode.UNAUTHORIZED.getCode(), msg));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.warn("写入 401 响应失败: {}", e.getMessage());
            return response.setComplete();
        }
    }
}
