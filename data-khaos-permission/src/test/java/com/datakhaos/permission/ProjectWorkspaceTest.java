package com.datakhaos.permission;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.config.MybatisPlusConfig;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.permission.controller.ProjectGroupController;
import com.datakhaos.permission.controller.ProjectWorkspaceActivityController;
import com.datakhaos.permission.mapper.ProjectWorkspaceActivityMapper;
import com.datakhaos.permission.mapper.SgProjectGroupMapper;
import com.datakhaos.permission.mapper.SgProjectGroupMemberMapper;
import com.datakhaos.permission.mapper.SgProjectGroupResourceMapper;
import com.datakhaos.permission.mapper.SgProjectRoleMapper;
import com.datakhaos.permission.mapper.SysRoleMapper;
import com.datakhaos.permission.mapper.SysUserRoleMapper;
import com.datakhaos.permission.service.PermissionService;
import com.datakhaos.permission.service.ProjectGroupAccess;
import com.datakhaos.permission.service.ProjectGroupService;
import com.datakhaos.permission.service.ProjectWorkspaceActivityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class ProjectWorkspaceTest {
    private static final String API = "/api/permission/project-group";
    private static final String KEY = "project-workspace-test-secret-012345678901234567";
    private final ObjectMapper json = new ObjectMapper();
    private Connection keeper;
    private AnnotationConfigApplicationContext context;
    private MockMvc mvc;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        keeper = datasource.getConnection();
        String schema = Files.readString(Path.of("..", "db", "mysql-init.sql"));
        try (Statement statement = keeper.createStatement()) {
            for (String table : List.of("sys_user", "sys_user_role", "sys_role", "sg_project_group",
                    "sg_project_group_member", "sg_project_role", "sg_project_group_resource", "meta_datasource")) {
                String prefix = "CREATE TABLE IF NOT EXISTS " + table + " (";
                int start = schema.indexOf(prefix);
                statement.execute(schema.substring(start, schema.indexOf(";", start)));
            }
        }
        jdbc = new JdbcTemplate(datasource);
        jdbc.update("""
                INSERT INTO sys_user(id, username, password) VALUES
                ('alice','alice','test'), ('bob','bob','test'), ('carol','carol','test'), ('1','admin','test')
                """);
        jdbc.update("""
                INSERT INTO sg_project_group(id, org_id, project_name, status) VALUES
                ('a','org','零售',1), ('b','org','营销',1), ('off','org','归档',0)
                """);
        jdbc.update("""
                INSERT INTO sg_project_role(id, org_id, project_group_id, role_name, role_code, capability_flags, status) VALUES
                ('lead','org','a','组长','PG_LEADER','["pg:manage","task:develop"]',1),
                ('viewer','org',NULL,'使用者','PG_USER','[]',1),
                ('b-lead','org','b','组长','PG_LEADER','["pg:manage"]',1),
                ('expired','org','b','已停用角色','PG_DEV','["pg:manage"]',0)
                """);
        jdbc.update("""
                INSERT INTO sg_project_group_member(id, project_group_id, user_id, project_role_id, is_primary) VALUES
                ('ma','a','alice','lead',1), ('mb','b','alice','viewer',0), ('mc','off','alice','lead',0),
                ('md','b','bob','expired',1)
                """);
        jdbc.update("INSERT INTO meta_datasource (id, ds_name, ds_type, password) VALUES ('retail-connection', '零售数据源', 'MYSQL', 'test-only')");
        jdbc.update("""
                INSERT INTO sg_project_group_resource(id, project_group_id, resource_type, resource_id) VALUES
                ('ra','a','DATASOURCE','retail-connection'), ('rb','b','TASK','marketing-task')
                """);

        var config = new MybatisConfiguration();
        config.addInterceptor(new MybatisPlusConfig().mybatisPlusInterceptor(new StandardEnvironment()));
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), datasource));
        for (Class<?> mapper : List.of(SgProjectGroupMapper.class, SgProjectGroupMemberMapper.class, SgProjectRoleMapper.class,
                SgProjectGroupResourceMapper.class, SysUserRoleMapper.class, SysRoleMapper.class,
                ProjectWorkspaceActivityMapper.class)) {
            config.addMapper(mapper);
        }
        var sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        context = new AnnotationConfigApplicationContext();
        context.register(Transactions.class);
        context.registerBean(DataSourceTransactionManager.class, () -> new DataSourceTransactionManager(datasource));
        context.registerBean(ProjectGroupService.class, () -> new ProjectGroupService(sql.getMapper(SgProjectGroupMapper.class),
                sql.getMapper(SgProjectGroupMemberMapper.class), sql.getMapper(SgProjectRoleMapper.class),
                sql.getMapper(SgProjectGroupResourceMapper.class)));
        context.refresh();
        ProjectGroupService groups = context.getBean(ProjectGroupService.class);
        PermissionService permissions = new PermissionService(sql.getMapper(SysUserRoleMapper.class),
                sql.getMapper(SysRoleMapper.class), null, null, null, null, groups);
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> method.invoke(((ServletRequestAttributes)
                        RequestContextHolder.currentRequestAttributes()).getRequest(), args));
        ProjectGroupAccess access = new ProjectGroupAccess(request, groups, permissions, KEY);
        ProjectWorkspaceActivityService activity = new ProjectWorkspaceActivityService(access,
                sql.getMapper(ProjectWorkspaceActivityMapper.class));
        mvc = MockMvcBuilders.standaloneSetup(new ProjectGroupController(groups, access),
                        new ProjectWorkspaceActivityController(activity, new com.datakhaos.permission.service.ProjectRunOperations(access, jdbc)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() throws Exception {
        if (context != null) context.close();
        if (keeper != null) keeper.close();
    }

    @Test
    void returnsOnlyActiveMembershipsAndProjectLocalCapabilities() throws Exception {
        JsonNode workspaces = read(get(API + "/me"), "alice");
        assertEquals(0, workspaces.path("code").asInt(-1));
        assertEquals(2, workspaces.path("data").size());
        assertEquals("a", workspaces.at("/data/0/projectGroupId").asText());
        assertEquals("pg:manage", workspaces.at("/data/0/capabilities/0").asText());
        assertEquals("PG_USER", workspaces.at("/data/1/roleCode").asText());
        assertTrue(read(get(API + "/b/workspace"), "bob").at("/data/capabilities").isEmpty());
        assertEquals(404, read(get(API + "/a/resources"), "bob").path("code").asInt());
        assertEquals("retail-connection", read(get(API + "/a/resources"), "alice").at("/data/0/resourceId").asText());
        JsonNode scoped = read(get(API + "/a/workspace/resources"), "alice");
        assertEquals("零售数据源", scoped.at("/data/0/name").asText());
        assertTrue(scoped.at("/data/0/available").asBoolean());
        assertFalse(scoped.at("/data/0").has("password"));
        assertEquals(404, read(get(API + "/a/workspace/resources"), "bob").path("code").asInt());
        assertEquals(403, read(get(API + "/user/alice"), "bob").path("code").asInt());
        assertEquals(401, response(get(API + "/me").header("X-User-Id", "alice")).path("code").asInt());
    }

    @Test
    void recordsMemberChangesOnlyWithinManagedProject() throws Exception {
        assertEquals(403, setMember("b", "carol", "viewer", "alice"));
        assertEquals(400, setMember("a", "carol", "b-lead", "alice"));
        assertEquals(404, setMember("a", "unknown", "viewer", "alice"));
        assertEquals(0, setMember("a", "carol", "viewer", "alice"));
        assertEquals("a", read(get(API + "/me"), "carol").at("/data/0/projectGroupId").asText());
        assertEquals(403, setMember("a", "alice", "viewer", "alice"));
        assertEquals(403, read(delete(API + "/a/members/alice"), "alice").path("code").asInt());
        assertEquals(0, read(delete(API + "/a/members/carol"), "alice").path("code").asInt(-1));
        assertEquals(404, read(get(API + "/a/workspace"), "carol").path("code").asInt());
        assertEquals(403, read(post(API + "/a/resources").contentType("application/json").content("[]"), "alice")
                .path("code").asInt());
    }

    @Test
    void rejectsExpiredTokensAndDisabledAccounts() throws Exception {
        String expired = JwtUtil.createToken("alice", "alice", KEY, -60);
        assertEquals(401, response(get(API + "/me").header("Authorization", "Bearer " + expired)).path("code").asInt());
        jdbc.update("UPDATE sys_user SET status = 0 WHERE id = 'alice'");
        assertEquals(401, read(get(API + "/me"), "alice").path("code").asInt());
    }

    @Test
    void readsMixedTasksAndImmutableRunOwnershipWithinSelectedProject() throws Exception {
        createActivityFixture();
        JsonNode first = read(get(API + "/a/workspace/tasks").param("size", "1"), "alice");
        assertEquals(0, first.path("code").asInt(-1), first::toString);
        assertEquals(2, first.at("/data/total").asInt());
        assertEquals("WORKFLOW", first.at("/data/records/0/kind").asText());
        assertEquals(1, first.at("/data/records").size());
        assertEquals("a-pipe", read(get(API + "/a/workspace/tasks").param("kind", "PIPELINE")
                .param("keyword", "orders"), "alice").at("/data/records/0/id").asText());
        assertEquals(0, read(get(API + "/a/workspace/tasks").param("keyword", "%"), "alice").at("/data/total").asInt());
        assertEquals(404, read(get(API + "/a/workspace/tasks"), "bob").path("code").asInt());
        assertEquals(400, read(get(API + "/a/workspace/runs").param("bizDate", "2026-02-30"), "alice").path("code").asInt());

        JsonNode aRuns = read(get(API + "/a/workspace/runs").param("bizDate", "2026-11-19"), "alice");
        assertEquals(0, aRuns.path("code").asInt(-1), aRuns::toString);
        assertEquals(2, aRuns.at("/data/total").asInt());
        assertEquals("a-run", aRuns.at("/data/records/0/id").asText());
        assertFalse(aRuns.at("/data/records/0").has("errorMessage"));
        assertEquals("moved-old", aRuns.at("/data/records/1/id").asText());
        assertTrue(aRuns.at("/data/records/1/taskName").isNull());
        assertEquals("b-run", read(get(API + "/b/workspace/runs"), "bob").at("/data/records/0/id").asText());
        assertEquals(1, read(get(API + "/b/workspace/runs"), "bob").at("/data/total").asInt());
        assertEquals(404, read(get(API + "/a/workspace/runs"), "bob").path("code").asInt());

        JsonNode filtered = read(get(API + "/a/workspace/runs").param("kind", "WORKFLOW")
                .param("status", "FAILED").param("taskId", "a-wf").param("triggerType", "BACKFILL")
                .param("size", "1"), "alice");
        assertEquals(1, filtered.at("/data/total").asInt());
        assertEquals("a-run", filtered.at("/data/records/0/id").asText());
        assertEquals(0, read(get(API + "/a/workspace/runs").param("taskId", "a-wf")
                .param("status", "SUCCESS"), "alice").at("/data/total").asInt());
        assertEquals(0, read(get(API + "/a/workspace/runs").param("taskId", "b-pipe")
                .param("triggerType", "WORKFLOW"), "alice").at("/data/total").asInt());
        assertEquals(400, read(get(API + "/a/workspace/runs").param("status", "invalid"), "alice").path("code").asInt());
        assertEquals(400, read(get(API + "/a/workspace/runs").param("triggerType", "invalid"), "alice").path("code").asInt());
        assertEquals(400, read(get(API + "/a/workspace/runs").param("taskId", "x".repeat(33)), "alice").path("code").asInt());
        assertEquals(404, read(get(API + "/a/workspace/runs").param("status", "FAILED"), "bob").path("code").asInt());

        jdbc.update("DELETE FROM sg_project_group_member WHERE user_id = 'alice' AND project_group_id = 'a'");
        assertEquals(404, read(get(API + "/a/workspace/runs"), "alice").path("code").asInt());
    }

    @Test
    void taskAndRunDetailsUseProjectOwnershipAndOmitPrivilegedFields() throws Exception {
        createActivityFixture();
        jdbc.execute("""
                CREATE TABLE workflow_node (id VARCHAR(32), wf_id VARCHAR(32), node_code VARCHAR(100),
                    node_name VARCHAR(100), node_type VARCHAR(32), config_json VARCHAR(500), deleted INT, create_time TIMESTAMP)
                """);
        jdbc.execute("""
                CREATE TABLE workflow_node_run (id VARCHAR(32), run_id VARCHAR(32), node_code VARCHAR(100),
                    node_name VARCHAR(100), node_type VARCHAR(32), status VARCHAR(20), duration_ms BIGINT,
                    start_time TIMESTAMP, end_time TIMESTAMP, create_time TIMESTAMP, log_text VARCHAR(500), ref_type VARCHAR(32), ref_id VARCHAR(32))
                """);
        jdbc.update("INSERT INTO workflow_node VALUES ('n1','a-wf','load','订单加载','PIPELINE','internal-config',0,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO workflow_node_run VALUES ('nr1','a-run','load','订单加载','PIPELINE','FAILED',100,NULL,NULL,CURRENT_TIMESTAMP,'internal-log','PIPELINE','test-instance')");
        jdbc.update("UPDATE pipeline_task SET engine='DB_SYNC', source_ds_id='retail-connection' WHERE id='a-pipe'");

        JsonNode workflow = read(get(API + "/a/workspace/tasks/WORKFLOW/a-wf"), "alice");
        assertEquals(0, workflow.path("code").asInt(-1), workflow::toString);
        assertEquals("load", workflow.at("/data/nodes/0/code").asText());
        assertFalse(workflow.toString().contains("internal-config"));
        JsonNode pipeline = read(get(API + "/a/workspace/tasks/PIPELINE/a-pipe"), "alice");
        assertEquals("零售数据源", pipeline.at("/data/sourceName").asText());
        assertTrue(pipeline.at("/data/targetName").isNull());
        assertEquals(404, read(get(API + "/a/workspace/tasks/PIPELINE/b-pipe"), "alice").path("code").asInt());
        assertEquals(404, read(get(API + "/a/workspace/tasks/WORKFLOW/a-wf"), "bob").path("code").asInt());

        JsonNode run = read(get(API + "/a/workspace/runs/WORKFLOW/a-run"), "alice");
        assertEquals("FAILED", run.at("/data/nodes/0/status").asText());
        assertFalse(run.toString().contains("internal-log"));
        assertFalse(run.toString().contains("example secret"));
        assertEquals(404, read(get(API + "/a/workspace/runs/WORKFLOW/legacy-run"), "alice").path("code").asInt());
        assertEquals(404, read(get(API + "/b/workspace/runs/PIPELINE/moved-old"), "alice").path("code").asInt());
        jdbc.update("UPDATE pipeline_instance SET status=2, workspace_request_id='request', error_message='测试可读错误' WHERE id='b-run'");
        assertEquals("测试可读错误", read(get(API + "/b/workspace/runs/PIPELINE/b-run"),"bob")
                .at("/data/failureReason").asText());
        assertEquals(400, read(get(API + "/a/workspace/tasks/OTHER/a-wf"), "alice").path("code").asInt());
    }

    private void createActivityFixture() {
        jdbc.execute("""
                CREATE TABLE workflow_def (
                  id VARCHAR(32), project_id VARCHAR(32), name VARCHAR(200), status INT, deleted INT,
                  create_time TIMESTAMP, update_time TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE pipeline_task (
                  id VARCHAR(32), project_group_id VARCHAR(32), task_name VARCHAR(200), status INT,
                  create_time TIMESTAMP, update_time TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE workflow_run (
                  id VARCHAR(32), project_group_id VARCHAR(32), wf_id VARCHAR(32), wf_name VARCHAR(200),
                  run_status VARCHAR(20), biz_date VARCHAR(10), start_time TIMESTAMP, create_time TIMESTAMP,
                  duration_ms BIGINT, trigger_type VARCHAR(20), error_message VARCHAR(500), recovery_code VARCHAR(40),
                  recovery_resolved_at TIMESTAMP, recovery_resolved_by VARCHAR(32), recovery_note VARCHAR(500),
                  alert_acknowledged_at TIMESTAMP, alert_acknowledged_by VARCHAR(32)
                )
                """);
        jdbc.execute("""
                CREATE TABLE pipeline_instance (
                  id VARCHAR(32), project_group_id VARCHAR(32), task_id VARCHAR(32), status INT, biz_date VARCHAR(10),
                  start_time TIMESTAMP, create_time TIMESTAMP, duration_ms BIGINT, trigger_type VARCHAR(20), workflow_run_id VARCHAR(32)
                )
                """);
        jdbc.execute("ALTER TABLE workflow_run ADD end_time TIMESTAMP");
        jdbc.execute("ALTER TABLE pipeline_instance ADD end_time TIMESTAMP");
        jdbc.execute("ALTER TABLE pipeline_instance ADD workspace_request_id VARCHAR(36)");
        jdbc.execute("ALTER TABLE pipeline_instance ADD error_message TEXT");
        jdbc.execute("ALTER TABLE pipeline_task ADD engine VARCHAR(32)");
        jdbc.execute("ALTER TABLE pipeline_task ADD source_ds_id VARCHAR(32)");
        jdbc.execute("ALTER TABLE pipeline_task ADD target_ds_id VARCHAR(32)");
        jdbc.execute("ALTER TABLE pipeline_task ADD write_mode VARCHAR(32)");
        jdbc.execute("ALTER TABLE pipeline_task ADD source_query TEXT");
        jdbc.execute("ALTER TABLE pipeline_task ADD field_mapping TEXT");
        jdbc.update("""
                INSERT INTO workflow_def VALUES
                  ('a-wf','a','订单数仓',2,0,TIMESTAMP '2026-11-19 04:00:00',NULL),
                  ('deleted','a','已删除',1,1,TIMESTAMP '2026-11-19 05:00:00',NULL),
                  ('legacy',NULL,'无项目',1,0,TIMESTAMP '2026-11-19 06:00:00',NULL)
                """);
        jdbc.update("""
                INSERT INTO pipeline_task (id,project_group_id,task_name,status,create_time,update_time) VALUES
                  ('a-pipe','a','Orders Pipeline',1,TIMESTAMP '2026-11-19 00:00:00',NULL),
                  ('b-pipe','b','营销任务',1,TIMESTAMP '2026-11-19 01:00:00',NULL)
                """);
        jdbc.update("""
                INSERT INTO workflow_run (id,project_group_id,wf_id,wf_name,run_status,biz_date,start_time,create_time,duration_ms,trigger_type,error_message) VALUES
                  ('a-run','a','a-wf','订单数仓','FAILED','2026-11-19',TIMESTAMP '2026-11-19 05:30:00',
                  NULL,100,'BACKFILL','example secret'),
                  ('legacy-run',NULL,'a-wf','无归属运行','SUCCESS','2026-11-19',TIMESTAMP '2026-11-19 06:30:00',
                  NULL,200,'MANUAL',NULL)
                """);
        jdbc.update("""
                INSERT INTO pipeline_instance (id,project_group_id,task_id,status,biz_date,start_time,create_time,duration_ms,trigger_type) VALUES
                  ('b-run','b','b-pipe',1,'2026-11-19',NULL,TIMESTAMP '2026-11-19 01:00:00',100,'WORKFLOW'),
                  ('moved-old','a','b-pipe',1,'2026-11-19',NULL,TIMESTAMP '2026-11-19 01:00:00',200,'MANUAL')
                """);
    }

    @Test
    void recoveryAndAlertAreSeparateAndCannotReleaseAnActivePipeline() throws Exception {
        createActivityFixture();
        jdbc.execute("CREATE TABLE workflow_node_run (run_id VARCHAR(32), status VARCHAR(25), end_time TIMESTAMP)");
        jdbc.execute("CREATE TABLE workflow_bizdate_lock (run_id VARCHAR(32))");
        jdbc.update("INSERT INTO workflow_bizdate_lock VALUES ('a-run')");
        jdbc.update("INSERT INTO workflow_node_run VALUES ('a-run', 'RUNNING', NULL)");
        jdbc.update("UPDATE workflow_run SET run_status='RECOVERY_REQUIRED', recovery_code='HEARTBEAT_EXPIRED' WHERE id='a-run'");
        jdbc.update("UPDATE pipeline_instance SET workflow_run_id='a-run', status=0 WHERE id='moved-old'");
        jdbc.update("UPDATE sg_project_role SET capability_flags='[\"task:schedule\"]' WHERE id='lead'");

        jdbc.update("""
                INSERT INTO sg_project_group_member(id, project_group_id, user_id, project_role_id, is_primary)
                VALUES ('review-reader', 'a', 'carol', 'viewer', 1)
                """);
        String urls = API + "/a/workspace/runs";
        String run = urls + "/WORKFLOW/a-run";
        assertEquals(1, read(get(urls).param("unacknowledgedOnly", "true"), "alice").at("/data/total").asInt());
        assertEquals(404, read(post(run + "/acknowledge"), "bob").path("code").asInt());
        assertEquals(403, read(post(run + "/acknowledge"), "carol").path("code").asInt());
        assertEquals(0, read(post(run + "/acknowledge"), "alice").path("code").asInt(-1));
        assertEquals(0, read(post(run + "/acknowledge"), "alice").path("code").asInt(-1));
        assertEquals("alice", jdbc.queryForObject("SELECT alert_acknowledged_by FROM workflow_run WHERE id='a-run'", String.class));
        assertEquals(0, read(get(urls).param("unacknowledgedOnly", "true"), "alice").at("/data/total").asInt());

        String resolve = run + "/resolve-recovery";
        assertEquals(400, read(post(resolve).contentType("application/json").content("{\"note\":\"  \"}"), "alice").path("code").asInt());
        String note = "{\"note\":\"verified in test\"}";
        assertEquals(5001, read(post(resolve).contentType("application/json").content(note), "alice").path("code").asInt());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_bizdate_lock", Integer.class));
        jdbc.update("UPDATE pipeline_instance SET status=1 WHERE id='moved-old'");
        assertEquals(0, read(post(resolve).contentType("application/json").content(note), "alice").path("code").asInt(-1));
        assertEquals(0, read(post(resolve).contentType("application/json").content(note), "alice").path("code").asInt(-1));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workflow_bizdate_lock", Integer.class));
        assertEquals("FAILED", jdbc.queryForObject("SELECT run_status FROM workflow_run WHERE id='a-run'", String.class));
    }

    private int setMember(String group, String user, String role, String actor) throws Exception {
        return read(put(API + "/" + group + "/members/" + user).contentType("application/json")
                .content("{\"projectRoleId\":\"" + role + "\"}"), actor).path("code").asInt(-1);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, String user) throws Exception {
        String token = JwtUtil.createToken(user, user, KEY, 60);
        return response(request.header("Authorization", "Bearer " + token));
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsByteArray());
    }

    @EnableTransactionManagement
    static class Transactions {}
}
