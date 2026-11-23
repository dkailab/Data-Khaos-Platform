package com.datakhaos.notification.monitor;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

@Component
class MonitorProbe {
    private final DiscoveryClient discovery;

    MonitorProbe(DiscoveryClient discovery) {
        this.discovery = discovery;
    }

    Sample check(String serviceId) {
        List<ServiceInstance> instances;
        try {
            instances = discovery.getInstances(serviceId);
        } catch (RuntimeException e) {
            return new Sample(MonitorStatus.UNKNOWN, 0, 0, "DISCOVERY_ERROR");
        }
        if (instances.isEmpty()) {
            return new Sample(MonitorStatus.DOWN, 0, 0, "UNREGISTERED");
        }
        int reachable = (int) instances.stream().filter(this::connect).count();
        if (reachable == instances.size()) {
            return new Sample(MonitorStatus.UP, instances.size(), reachable, null);
        }
        MonitorStatus status = reachable == 0 ? MonitorStatus.DOWN : MonitorStatus.DEGRADED;
        return new Sample(status, instances.size(), reachable, "INSTANCE_UNREACHABLE");
    }

    private boolean connect(ServiceInstance instance) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(instance.getHost(), instance.getPort()), 750);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    record Sample(MonitorStatus status, int registeredCount, int reachableCount, String reason) {}
}
