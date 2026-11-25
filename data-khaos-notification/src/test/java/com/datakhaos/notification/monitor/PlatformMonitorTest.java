package com.datakhaos.notification.monitor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.common.security.MetadataContextFilter;
import com.datakhaos.notification.controller.InternalFlinkNotificationController;
import com.datakhaos.notification.controller.NotificationController;
import com.datakhaos.workflow.api.model.FlinkAlertEvent;
import com.datakhaos.notification.mapper.NotifyRecordMapper;
import com.datakhaos.notification.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class PlatformMonitorTest {
    private static final String SERVICE = "data-khaos-query";
    private static final String API = "/api/notify/monitor";
    private static final String SECRET = "platform-monitor-tests-key-012345678901234567";
    private static final MonitorProbe.Sample UP = new MonitorProbe.Sample(MonitorStatus.UP, 2, 2, null);
    private static final MonitorProbe.Sample PARTIAL =
            new MonitorProbe.Sample(MonitorStatus.DEGRADED, 2, 1, "INSTANCE_UNREACHABLE");
    private static final MonitorProbe.Sample DOWN =
            new MonitorProbe.Sample(MonitorStatus.DOWN, 0, 0, "UNREGISTERED");
    private static final MonitorProbe.Sample UNKNOWN =
            new MonitorProbe.Sample(MonitorStatus.UNKNOWN, 0, 0, "DISCOVERY_ERROR");

    private final ObjectMapper json = new ObjectMapper();
    private AnnotationConfigApplicationContext context;
    private Connection keepAlive;
    private JdbcTemplate jdbc;
    private MonitorService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        keepAlive = datasource.getConnection();
        try (Statement statement = keepAlive.createStatement()) {
            String migration = Files.readString(Path.of("..", "db", "migrate-platform-monitor.sql"))
                    .replace("USE data_khaos;", "");
            statement.execute(migration);
            String schema = Files.readString(Path.of("..", "db", "mysql-init.sql"));
            String recordTable = schema.split("CREATE TABLE IF NOT EXISTS notify_record", 2)[1].split(";", 2)[0];
            statement.execute("CREATE TABLE IF NOT EXISTS notify_record" + recordTable);
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), datasource));
        configuration.addMapper(NotifyRecordMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        jdbc = new JdbcTemplate(datasource);
        context = new AnnotationConfigApplicationContext();
        context.register(TxConfig.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(MonitorRepository.class, () -> new MonitorRepository(jdbc));
        context.registerBean(NotificationService.class,
                () -> new NotificationService(null, session.getMapper(NotifyRecordMapper.class), null, null));
        context.registerBean(MonitorService.class);
        context.refresh();

        service = context.getBean(MonitorService.class);
        var notifications = context.getBean(NotificationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MonitorController(service, SECRET),
                        new InternalFlinkNotificationController(notifications, SECRET), new NotificationController(notifications))
                .addFilters(new MetadataContextFilter())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() throws Exception {
        if (context != null) context.close();
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void firesOnceAcrossConcurrentSamplesAndSendsRecovery() throws Exception {
        sample(1, PARTIAL);
        sample(2, DOWN);
        assertEquals(0, service.alerts(1, 10, true).getTotal());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { await(start); sample(3, DOWN); });
            var second = pool.submit(() -> { await(start); sample(3, DOWN); });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        sample(4, DOWN);
        sample(2, UP);
        assertEquals(1, service.alerts(1, 10, true).getTotal());
        assertEquals(1, messageCount());
        var alert = service.alerts(1, 10, true).getRecords().get(0);
        assertEquals("UNREGISTERED", alert.reason());
        assertEquals(alert.id(), status().activeAlertId());

        sample(5, UP);
        sample(6, UNKNOWN);
        sample(7, UP);
        assertEquals(1, service.alerts(1, 10, true).getTotal());
        sample(8, UP);
        assertEquals(0, service.alerts(1, 10, true).getTotal());
        assertEquals(2, messageCount());
        assertNotNull(service.alerts(1, 10, false).getRecords().get(0).recoveredAt());
        assertNull(status().activeAlertId());
    }

    @Test
    void rollsBackOpenAlertIfNotificationStorageFails() {
        sample(1, DOWN);
        sample(2, DOWN);
        jdbc.execute("ALTER TABLE notify_record ADD CONSTRAINT reject_site CHECK (channel <> 'SITE')");
        assertThrows(RuntimeException.class, () -> sample(3, DOWN));
        assertEquals(0, service.alerts(1, 10, true).getTotal());
        assertNull(status().activeAlertId());
        jdbc.execute("ALTER TABLE notify_record DROP CONSTRAINT reject_site");
        sample(3, DOWN);
        assertEquals(1, service.alerts(1, 10, true).getTotal());
        assertEquals(1, messageCount());
    }

    @Test
    void verifiesAdminTokenAndAcknowledgesIdempotently() throws Exception {
        sample(1, DOWN);
        sample(2, DOWN);
        sample(3, DOWN);
        String alert = status().activeAlertId();

        assertEquals(401, body(get(API + "/services").header("X-User-Id", "1")).path("code").asInt());
        assertEquals(401, body(get(API + "/services").header("Authorization", token("1", -120))).path("code").asInt());
        assertEquals(403, body(get(API + "/alerts").header("Authorization", token("2", 120))).path("code").asInt());
        assertEquals(403, body(post(API + "/alerts/" + alert + "/acknowledge")
                .header("Authorization", token("2", 120))).path("code").asInt());
        JsonNode services = body(get(API + "/services").header("Authorization", token("1", 120)));
        assertEquals(0, services.path("code").asInt(-1));
        assertTrue(services.path("data").size() > 1);

        JsonNode first = body(post(API + "/alerts/" + alert + "/acknowledge")
                .header("Authorization", token("1", 120)));
        JsonNode repeated = body(post(API + "/alerts/" + alert + "/acknowledge")
                .header("Authorization", token("1", 120)));
        assertEquals("1", first.at("/data/acknowledgedBy").asText());
        assertEquals(first.at("/data/acknowledgedAt"), repeated.at("/data/acknowledgedAt"));
        assertEquals(1, service.alerts(1, 10, true).getTotal());
    }

    @Test
    void requiresServiceTokenDeduplicatesEventsAndScopesTheInbox() throws Exception {
        var open = new FlinkAlertEvent("alert-1", "run-1", "operator-1", FlinkAlertEvent.Type.OPEN);
        var recovered = new FlinkAlertEvent("alert-1", "run-1", "operator-1", FlinkAlertEvent.Type.RECOVERED);
        assertEquals(403, body(event(open).header("X-User-Id", "1")).path("code").asInt());
        assertEquals(403, body(event(open).header(FlinkAlertEvent.TOKEN_HEADER, SECRET + "invalid"))
                .path("code").asInt());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notify_record", Integer.class));

        var concurrent = Executors.newFixedThreadPool(2);
        try {
            var first = concurrent.submit(() -> body(event(open).header(FlinkAlertEvent.TOKEN_HEADER, SECRET)));
            var retry = concurrent.submit(() -> body(event(open).header(FlinkAlertEvent.TOKEN_HEADER, SECRET)));
            assertTrue(first.get(5, TimeUnit.SECONDS).at("/data").asBoolean());
            assertTrue(retry.get(5, TimeUnit.SECONDS).at("/data").asBoolean());
        } finally {
            concurrent.shutdownNow();
        }
        assertTrue(body(event(recovered).header(FlinkAlertEvent.TOKEN_HEADER, SECRET)).at("/data").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE receiver_id = 'operator-1'", Integer.class));

        jdbc.update("""
                INSERT INTO notify_record(id, receiver_id, receiver_type, channel, status)
                VALUES ('role-record', 'operator-1', 'ROLE', 'SITE', 1)
                """);
        var inbox = "/api/notify/inbox";
        assertEquals(401, body(get(inbox).param("receiverId", "operator-1")).path("code").asInt());
        assertEquals(0, body(get(inbox).header("X-User-Id", "other").param("receiverId", "operator-1"))
                .at("/data/records").size());
        assertEquals(2, body(get(inbox).header("X-User-Id", "operator-1")).at("/data/records").size());
    }

    @Test
    void pressureMessagesHaveIndependentOpeningAndRecoveryRecords() throws Exception {
        var open = new FlinkAlertEvent("pressure-1", "run-1", "operator-1", FlinkAlertEvent.Type.PRESSURE_OPEN);
        var clear = new FlinkAlertEvent("pressure-1", "run-1", "operator-1", FlinkAlertEvent.Type.PRESSURE_CLEAR);
        for (var transition : List.of(open, clear, open, clear)) {
            assertTrue(body(event(transition).header(FlinkAlertEvent.TOKEN_HEADER, SECRET)).at("/data").asBoolean());
        }
        var titles = jdbc.queryForList("SELECT title FROM notify_record WHERE receiver_id = 'operator-1'", String.class);
        assertEquals(2, titles.size());
        assertTrue(titles.containsAll(List.of("Flink 作业持续反压", "Flink 作业反压已恢复")));
    }

    private MockHttpServletRequestBuilder event(FlinkAlertEvent event) throws Exception {
        return post(FlinkAlertEvent.NOTIFICATION_PATH).contentType("application/json").content(json.writeValueAsBytes(event));
    }

    @Test
    void emptyDiscoveryAndRegistryFailureHaveDifferentStates() {
        assertEquals(DOWN, new MonitorProbe(discovery(false)).check(SERVICE));
        assertEquals(UNKNOWN, new MonitorProbe(discovery(true)).check(SERVICE));
    }

    private DiscoveryClient discovery(boolean failing) {
        return new DiscoveryClient() {
            @Override public String description() { return "测试注册中心"; }
            @Override public List<String> getServices() { return List.of(); }
            @Override public List<ServiceInstance> getInstances(String serviceId) {
                if (failing) throw new IllegalStateException("registry down");
                return List.of();
            }
        };
    }

    private MonitorViews.ServiceStatus status() {
        return service.services().stream().filter(row -> SERVICE.equals(row.serviceId())).findFirst().orElseThrow();
    }

    private void sample(long bucket, MonitorProbe.Sample sample) {
        service.record(SERVICE, bucket, LocalDateTime.of(2026, 11, 20, 0, 0).plusSeconds(bucket * 30), sample);
    }

    private int messageCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notify_record WHERE receiver_id = '1' AND channel = 'SITE'",
                Integer.class);
    }

    private String token(String userId, long seconds) {
        return "Bearer " + JwtUtil.createToken(userId, "test-user", SECRET, seconds);
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsByteArray());
    }

    private static void await(CountDownLatch start) {
        try {
            assertTrue(start.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @EnableTransactionManagement
    static class TxConfig {}
}
