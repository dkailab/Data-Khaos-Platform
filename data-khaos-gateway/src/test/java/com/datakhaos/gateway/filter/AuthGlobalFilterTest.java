package com.datakhaos.gateway.filter;

import com.datakhaos.common.constant.CommonConstants;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.gateway.config.GatewayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthGlobalFilterTest {
    private static final String VERSION = "/api/data/v1/projects/orders/apis/region-sales/versions/1";
    private static final String TOKEN = "Bearer dkwh_" + "A".repeat(43);
    private final GatewayProperties properties = new GatewayProperties();
    private final AuthGlobalFilter filter = new AuthGlobalFilter(properties, new ObjectMapper());

    @Test
    void passesMachineAuthorizationOnlyToVersionedReadsAndDiscardsSpoofedIdentity() {
        var exchange = request(HttpMethod.GET, VERSION, TOKEN);
        var forwarded = downstream(exchange);
        assertNotNull(forwarded);
        var headers = forwarded.getRequest().getHeaders();
        assertEquals(TOKEN, headers.getFirst(HttpHeaders.AUTHORIZATION));
        assertNull(headers.getFirst(CommonConstants.HEADER_USER_ID));
        assertNull(headers.getFirst(CommonConstants.HEADER_ROLES));
        assertNull(headers.getFirst(CommonConstants.HEADER_USERNAME));

        assertRejected(request(HttpMethod.POST, VERSION, TOKEN));
        assertRejected(request(HttpMethod.GET, VERSION + "/extra", TOKEN));
        assertRejected(request(HttpMethod.GET, "/api/data/v1/order-region-gmv", TOKEN));
        assertRejected(request(HttpMethod.POST, "/api/query/warehouse/v1/projects/orders/apis/region-sales/versions/1/credentials", TOKEN));
    }

    @Test
    void verifiesPlatformTokenAndRejectsExpiredCredentialIssuers() {
        String valid = JwtUtil.createToken("developer", "dev", properties.getSecret(), 60);
        var forwarded = downstream(request(HttpMethod.GET, VERSION, "Bearer " + valid));
        assertNotNull(forwarded);
        assertEquals("developer", forwarded.getRequest().getHeaders().getFirst(CommonConstants.HEADER_USER_ID));
        assertNull(forwarded.getRequest().getHeaders().getFirst(CommonConstants.HEADER_ROLES));

        String expired = JwtUtil.createToken("developer", "dev", properties.getSecret(), -60);
        assertRejected(request(HttpMethod.POST, "/api/query/warehouse/v1/projects/orders/apis/region-sales/versions/1/credentials",
                "Bearer " + expired));
    }

    private MockServerWebExchange request(HttpMethod method, String path, String authorization) {
        return MockServerWebExchange.from(MockServerHttpRequest.method(method, path)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header(CommonConstants.HEADER_USER_ID, "spoofed")
                .header(CommonConstants.HEADER_USERNAME, "spoofed")
                .header(CommonConstants.HEADER_ROLES, "SUPER_ADMIN").build());
    }

    private ServerWebExchange downstream(MockServerWebExchange exchange) {
        var forwarded = new AtomicReference<ServerWebExchange>();
        filter.filter(exchange, next -> {
            forwarded.set(next);
            return next.getResponse().setComplete();
        }).block();
        return forwarded.get();
    }

    private void assertRejected(MockServerWebExchange exchange) {
        assertNull(downstream(exchange));
        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }
}
