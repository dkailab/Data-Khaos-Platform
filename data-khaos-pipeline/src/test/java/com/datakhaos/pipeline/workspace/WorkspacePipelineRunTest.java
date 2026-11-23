package com.datakhaos.pipeline.workspace;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.datakhaos.common.exception.GlobalExceptionHandler;
import com.datakhaos.common.security.JwtUtil;
import com.datakhaos.pipeline.engine.impl.DbSyncEngine;
import com.datakhaos.pipeline.entity.PipelineInstance;
import com.datakhaos.pipeline.entity.PipelineTask;
import com.datakhaos.pipeline.mapper.PipelineInstanceMapper;
import com.datakhaos.pipeline.mapper.PipelineTaskMapper;
import com.datakhaos.pipeline.service.PipelineRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class WorkspacePipelineRunTest {
    private static final String KEY = "test-workspace-pipeline-key";
    private static final String DAY = "2026-11-19";
    private final ObjectMapper json = new ObjectMapper();
    private final Queue<Runnable> pending = new ArrayDeque<>();
    private final AtomicInteger executions = new AtomicInteger();
    private Connection keepAlive;
    private JdbcTemplate jdbc;
    private PipelineRunner runner;
    private MockMvc mvc;
    private boolean queueFull;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource db = new JdbcDataSource();
        db.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL");
        keepAlive = db.getConnection();
        String schema = Files.readString(Path.of("..", "db", "mysql-init.sql"));
        try (Statement ddl = keepAlive.createStatement()) {
            for (String table : List.of("sys_user", "sg_project_group", "sg_project_group_member", "sg_project_role",
                    "sg_project_group_resource", "sys_table_permission", "sys_row_policy", "sys_column_policy",
                    "meta_datasource", "pipeline_task", "pipeline_instance")) {
                int start = schema.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
                ddl.execute(schema.substring(start, schema.indexOf(";", start)));
            }
        }
        jdbc = new JdbcTemplate(db);
        addColumns("pipeline_task", "write_mode VARCHAR(32)", "date_column VARCHAR(64)");
        addColumns("pipeline_instance", "project_group_id VARCHAR(32)", "workflow_run_id VARCHAR(32)",
                "workflow_node_run_id VARCHAR(32)", "biz_date VARCHAR(10)", "target_table VARCHAR(128)",
                "target_datasource_id VARCHAR(32)", "target_database_name VARCHAR(200)", "published_at TIMESTAMP", "materialization_generation CHAR(36)",
                "write_mode VARCHAR(32)", "workspace_user_id VARCHAR(32)", "workspace_request_id VARCHAR(36)",
                "workspace_active_key CHAR(64)");
        jdbc.execute("CREATE UNIQUE INDEX uk_pi_workspace_active ON pipeline_instance(workspace_active_key)");
        jdbc.execute("CREATE UNIQUE INDEX uk_pi_workspace_request ON pipeline_instance(project_group_id,task_id,workspace_user_id,workspace_request_id)");

        jdbc.update("INSERT INTO sys_user (id,username,password) VALUES ('dev','dev','test'), ('viewer','viewer','test')");
        jdbc.update("INSERT INTO sg_project_group (id,org_id,project_name) VALUES ('retail','org','零售'), ('market','org','营销')");
        jdbc.update("""
                INSERT INTO sg_project_role (id,role_name,capability_flags)
                VALUES ('dev-role','开发','["task:develop"]'), ('view-role','使用者','[]')
                """);
        jdbc.update("""
                INSERT INTO sg_project_group_member (id,project_group_id,user_id,project_role_id)
                VALUES ('m1','retail','dev','dev-role'), ('m2','retail','viewer','view-role')
                """);
        jdbc.update("""
                INSERT INTO meta_datasource (id,ds_name,ds_type,host,port,database_name,username)
                VALUES ('src','来源','MYSQL','mysql-source',3306,'orders_db','source_reader'),
                       ('dst','目标','MYSQL','mysql-target',3306,'warehouse','target_writer')
                """);
        jdbc.update("""
                INSERT INTO sg_project_group_resource (id,project_group_id,resource_type,resource_id)
                VALUES ('r1','retail','DATASOURCE','src'), ('r2','retail','DATASOURCE','dst')
                """);
        jdbc.update("""
                INSERT INTO sys_table_permission (id,project_group_id,datasource_id,database_name,table_name,permission_type,grant_type)
                VALUES ('p1','retail','src','orders_db','orders','SELECT','PROJECT_GROUP'),
                       ('p2','retail','dst','warehouse','ods_orders','INSERT','PROJECT_GROUP'),
                       ('p3','retail','dst','warehouse','ods_orders','DELETE','PROJECT_GROUP')
                """);
        jdbc.update("""
                INSERT INTO pipeline_task (id,task_name,project_group_id,engine,source_ds_id,source_table,
                    target_ds_id,target_table,date_column,write_mode)
                VALUES ('orders','订单同步','retail','DB_SYNC','src','orders','dst','ods_orders','biz_date','REPLACE_BY_BIZ_DATE'),
                       ('other','其他','retail','DB_SYNC','src','orders','dst','ods_orders','biz_date','REPLACE_BY_BIZ_DATE'),
                       ('foreign','其他项目','market','DB_SYNC','src','orders','dst','ods_orders','biz_date','REPLACE_BY_BIZ_DATE')
                """);

        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), db));
        config.addMapper(PipelineTaskMapper.class);
        config.addMapper(PipelineInstanceMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        PipelineInstanceMapper instances = session.getMapper(PipelineInstanceMapper.class);
        runner = new PipelineRunner(instances, null, jdbc);
        DbSyncEngine engine = new DbSyncEngine(jdbc, KEY) {
            @Override
            public int executeWorkspace(PipelineTask task, PipelineInstance instance,
                                        Map<String, Object> source, Map<String, Object> target) {
                assertEquals("orders_db", source.get("database_name"));
                assertEquals("warehouse", target.get("database_name"));
                assertEquals(DAY, instance.getBizDate());
                executions.incrementAndGet();
                return 7;
            }
        };
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor() {
            @Override
            public void execute(Runnable work) {
                if (queueFull) throw new RejectedExecutionException();
                pending.add(work);
            }
        };
        WorkspacePipelineRunService service = new WorkspacePipelineRunService(session.getMapper(PipelineTaskMapper.class),
                instances, new WorkspacePipelinePolicy(jdbc, KEY), runner, engine, executor);
        mvc = MockMvcBuilders.standaloneSetup(new WorkspacePipelineRunController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void close() throws Exception {
        if (keepAlive != null) keepAlive.close();
    }

    @Test
    void workflowAdmissionReusesExistingInstanceAndRejectsDelayedRecoverySubmission() {
        jdbc.execute("CREATE TABLE workflow_run (id VARCHAR(32) PRIMARY KEY, run_status VARCHAR(25))");
        jdbc.execute("CREATE TABLE workflow_node_run (id VARCHAR(32), run_id VARCHAR(32), node_type VARCHAR(32), status VARCHAR(25))");
        jdbc.update("INSERT INTO workflow_run VALUES ('wf-run', 'RUNNING')");
        jdbc.update("INSERT INTO workflow_node_run VALUES ('node', 'wf-run', 'PIPELINE', 'RUNNING')");
        PipelineTask task = new PipelineTask();
        task.setId("orders");
        task.setEngine("DB_SYNC");

        PipelineRunner.Admission first = runner.admitWorkflow(task, "wf-run", "node", DAY);
        PipelineRunner.Admission replay = runner.admitWorkflow(task, "wf-run", "node", DAY);
        assertTrue(first.newlyCreated());
        assertFalse(replay.newlyCreated());
        assertEquals(first.instance().getId(), replay.instance().getId());

        jdbc.update("UPDATE workflow_run SET run_status='RECOVERY_REQUIRED' WHERE id='wf-run'");
        org.junit.jupiter.api.Assertions.assertThrows(com.datakhaos.common.exception.BusinessException.class,
                () -> runner.admitWorkflow(task, "wf-run", "node", DAY));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_instance", Integer.class));
    }

    @Test
    void replaysSameRequestAndLocksSameTargetDayAcrossTasks() throws Exception {
        UUID key = UUID.randomUUID();
        JsonNode first = submit("orders","dev",key, DAY);
        assertEquals(0, first.path("code").asInt(-1), first::toString);
        String id = first.at("/data/runId").asText();
        assertEquals("ACCEPTED", first.at("/data/state").asText());
        JsonNode duplicate = submit("orders","dev",key, DAY);
        assertEquals(id, duplicate.at("/data/runId").asText());
        assertEquals("DUPLICATE", duplicate.at("/data/state").asText());
        assertEquals(5002, submit("orders","dev",key, "2026-11-18").path("code").asInt());
        assertEquals(5002, submit("other","dev",UUID.randomUUID(), DAY).path("code").asInt());
        assertEquals(1, pending.size());
        pending.remove().run();
        assertEquals(1, executions.get());
        assertEquals(1, status(id));
        assertEquals(7L, jdbc.queryForObject("SELECT `rows` FROM pipeline_instance WHERE id=?", Long.class, id));
        assertNull(jdbc.queryForObject("SELECT workspace_active_key FROM pipeline_instance WHERE id=?", String.class, id));
        assertEquals("ACCEPTED", submit("other","dev",UUID.randomUUID(), DAY).at("/data/state").asText());
    }

    @Test
    void validatesVerifiedIdentityExactGrantsAndSafeTaskForm() throws Exception {
        assertEquals(401, submit("orders",null,UUID.randomUUID(), DAY).path("code").asInt());
        assertEquals(403, submit("orders","viewer",UUID.randomUUID(), DAY).path("code").asInt());
        assertEquals(404, submit("foreign","dev",UUID.randomUUID(), DAY).path("code").asInt());
        assertEquals(400, submit("orders","dev",UUID.randomUUID(),"2026-02-30").path("code").asInt());

        jdbc.update("INSERT INTO sys_column_policy (id,policy_name,target_table,column_name) VALUES ('mask','按组脱敏','orders','sensitive')");
        assertEquals(403, submit("orders","dev",UUID.randomUUID(), DAY).path("code").asInt());
        jdbc.update("DELETE FROM sys_column_policy");
        jdbc.update("UPDATE sys_table_permission SET grant_type='USER' WHERE id='p3'");
        assertEquals(403, submit("orders","dev",UUID.randomUUID(), DAY).path("code").asInt());
        jdbc.update("UPDATE sys_table_permission SET grant_type='PROJECT_GROUP' WHERE id='p3'");
        jdbc.update("UPDATE pipeline_task SET source_query='SELECT 1' WHERE id='orders'");
        assertEquals(400, submit("orders","dev",UUID.randomUUID(), DAY).path("code").asInt());
        assertTrue(pending.isEmpty());
    }

    @Test
    void rechecksMembershipAndBindingsBeforeStarting() throws Exception {
        String first = submit("orders","dev",UUID.randomUUID(), DAY).at("/data/runId").asText();
        assertFalse(first.isEmpty());
        jdbc.update("DELETE FROM sg_project_group_member WHERE id='m1'");
        pending.remove().run();
        assertEquals(2, status(first));
        jdbc.update("INSERT INTO sg_project_group_member (id,project_group_id,user_id,project_role_id) VALUES ('m1','retail','dev','dev-role')");
        String second = submit("orders","dev",UUID.randomUUID(), DAY).at("/data/runId").asText();
        assertFalse(second.isEmpty());
        jdbc.update("DELETE FROM sg_project_group_resource WHERE id='r2'");
        pending.remove().run();
        assertEquals(2, status(second));
        assertEquals(0, executions.get());
    }

    @Test
    void queueRejectionRecordsFailureAndReleasesTargetDay() throws Exception {
        queueFull = true;
        JsonNode rejected = submit("orders","dev",UUID.randomUUID(), DAY);
        assertEquals("REJECTED", rejected.at("/data/state").asText(), rejected::toString);
        assertEquals(2, status(rejected.at("/data/runId").asText()));
        queueFull = false;
        assertEquals("ACCEPTED", submit("orders","dev",UUID.randomUUID(), DAY).at("/data/state").asText());
    }

    private int status(String id) {
        return jdbc.queryForObject("SELECT status FROM pipeline_instance WHERE id=?", Integer.class, id);
    }

    private void addColumns(String table, String... definitions) {
        for (String definition : definitions) jdbc.execute("ALTER TABLE " + table + " ADD " + definition);
    }

    private JsonNode submit(String task, String user, UUID key, String date) throws Exception {
        var request = post("/api/pipeline/workspaces/retail/tasks/" + task + "/runs")
                .contentType("application/json").content(json.writeValueAsString(Map.of("requestId", key, "bizDate", date)));
        if (user != null) request.header("Authorization", "Bearer " + JwtUtil.createToken(user, user, KEY, 60));
        else request.header("X-User-Id","dev");
        return json.readTree(mvc.perform(request).andReturn().getResponse().getContentAsByteArray());
    }
}
