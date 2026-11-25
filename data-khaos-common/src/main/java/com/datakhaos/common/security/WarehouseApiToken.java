package com.datakhaos.common.security;

public final class WarehouseApiToken {
    public static final String PREFIX = "dkwh_";
    public static final String DATA_ROUTE = "/api/data/v1/projects/{projectId}/apis/{apiCode}/versions/{version}";

    private WarehouseApiToken() {
    }

    public static boolean isMachineAuthorization(String authorization) {
        return authorization != null && authorization.startsWith("Bearer " + PREFIX);
    }
}
