/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import cn.tonglink.ai.AiService;
import cn.tonglink.execution.ExecutionService;
import cn.tonglink.repository.FlowRepository;
import cn.tonglink.scheduler.ScheduleService;
import cn.tonglink.security.CryptoService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.DispatcherType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 端到端集成测试：登录 → 建流程 → 执行 → 日志 → 统计 → AI 生成。
 *
 * <p>使用独立的 SQLite 临时库，避免污染开发数据；调度器间隔调大，
 * 防止后台定时任务在测试过程中自动触发流程造成干扰。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private FlowRepository flowRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CryptoService cryptoService;

    @Autowired
    private AiService aiService;

    private final ObjectMapper json = new ObjectMapper();

    /** 每个测试类使用唯一数据库文件，避免上次运行残留数据。 */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        Path db = Path.of("target", "it-" + System.nanoTime() + ".db").toAbsolutePath();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db);
        registry.add("tonglink.scheduler.interval-ms", () -> "3600000");
        // 测试里禁用自动对齐，避免种子流程注册 timer 干扰用例；手动调用 alignTimers/fire 驱动。
        registry.add("tonglink.scheduler.enabled", () -> "false");
        registry.add("tonglink.scheduler.timer-threads", () -> "2");
    }

    /** 插入一条已发布的定时流程，返回其图 JSON。 */
    private String insertScheduledFlow(String id, String nextRun) {
        String graph = """
                {
                  "schemaVersion": 1,
                  "settings": {},
                  "nodes": [
                    {"id":"trig","type":"trigger","subtype":"schedule","nodeVersion":"1","title":"定时",
                     "position":{"x":60,"y":120},
                     "config":{"mode":"minutes","everyMinutes":5,"enabled":"是","catchUp":"否"}},
                    {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"加工",
                     "position":{"x":360,"y":120},
                     "config":{"code":"return [{ok: true}]"},"config2":{}}
                  ],
                  "connections": [
                    {"id":"e1","source":"trig","target":"n2","sourceHandle":"out","targetHandle":"in"}
                  ]
                }
                """;
        String now = LocalDateTime.now().withNano(0).toString();
        jdbc.update("""
                INSERT INTO flow_definition(id,project_id,name,description,status,graph_json,
                                            published_graph_json,published_version,next_run,last_run,
                                            records,success_rate,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, "P-001", "定时测试流程", "", "active", graph, graph, 1, nextRun,
                "—", 0L, "100%", now, now);
        return graph;
    }

    /** 插入一条名称独特的流程，供 AI 上下文断言使用。 */
    private void insertNamedFlow(String id, String name) {
        String now = LocalDateTime.now().withNano(0).toString();
        jdbc.update("""
                INSERT INTO flow_definition(id,project_id,name,description,status,graph_json,
                                            published_graph_json,published_version,next_run,last_run,
                                            records,success_rate,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, "P-001", name, "", "active", "{}", "{}", 0, "—", "—", 9999L, "100%", now, now);
    }

    /** 精确 timer 在对齐间隔之外也能按计划时刻触发，不必等下一次 5 秒扫描。 */
    @Test
    void 精确timer在计划时刻触发() throws Exception {
        String id = "FLOW-TIMER-TEST";
        String next = LocalDateTime.now().withNano(0).plusSeconds(1).toString();
        insertScheduledFlow(id, next);

        scheduleService.alignTimers();
        Thread.sleep(2000);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution WHERE flow_id=? AND trigger_type='schedule'",
                Integer.class, id);
        assertThat(count).isEqualTo(1);
        String advanced = jdbc.queryForObject(
                "SELECT next_run FROM flow_definition WHERE id=?", String.class, id);
        assertThat(advanced).isNotEqualTo(next);
    }

    /** 两个实例看到同一个 next_run 时，只有一个能认领并投递执行。 */
    @Test
    void 多实例条件认领只触发一次() throws Exception {
        String id = "FLOW-CLAIM-TEST";
        String next = LocalDateTime.now().withNano(0).plusMinutes(10).toString();
        insertScheduledFlow(id, next);

        ScheduleService second = new ScheduleService(flowRepository, executionService, 2,
                3600000L, 60000L, false);
        try {
            scheduleService.fire(id, next);
            second.fire(id, next);
        } finally {
            second.close();
        }

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM execution WHERE flow_id=? AND trigger_type='schedule'",
                Integer.class, id);
        assertThat(count).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT next_run FROM flow_definition WHERE id=?", String.class, id))
                .isNotEqualTo(next);
    }

    /** 执行结束刷新统计时不能把调度器已推进的 next_run 写回旧值。 */
    @Test
    void 统计更新不回退下次执行时间() throws Exception {
        String id = "FLOW-STATS-TEST";
        String next = LocalDateTime.now().withNano(0).plusMinutes(7).toString();
        insertScheduledFlow(id, next);

        flowRepository.updateFlowStats(id, LocalDateTime.now().withNano(0).toString(), 42L);

        assertThat(jdbc.queryForObject(
                "SELECT next_run FROM flow_definition WHERE id=?", String.class, id))
                .isEqualTo(next);
        assertThat(jdbc.queryForObject(
                "SELECT records FROM flow_definition WHERE id=?", Long.class, id))
                .isEqualTo(42L);
    }

    /**
     * 历史遗留的 RUNNING 不能永久挡住定时触发；清理只处理超过阈值的记录。
     */
    @Test
    void 过期RUNNING不阻塞调度且清理只处理过期记录() throws Exception {
        String id = "FLOW-STALE-RUNNING-TEST";
        insertScheduledFlow(id, LocalDateTime.now().withNano(0).plusMinutes(10).toString());

        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
        String oldStart = LocalDateTime.now().minusDays(2).format(fmt);
        String freshStart = LocalDateTime.now().format(fmt);
        String insert = """
                INSERT INTO execution(id,flow_id,flow_version,trigger_type,status,start_time,end_time,
                                      records,error_summary,idempotency_key)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """;
        jdbc.update(insert, "EXEC-STALE-1", id, 1, "schedule", "RUNNING", oldStart, null, 0L, null, null);
        jdbc.update(insert, "EXEC-FRESH-1", id, 1, "schedule", "RUNNING", freshStart, null, 0L, null, null);

        // 有一条刚开始的 RUNNING 时仍然阻塞新周期
        assertThat(executionService.hasRunning(id)).isTrue();

        int cleaned = executionService.failStaleRunning();
        assertThat(cleaned).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM execution WHERE id=?", String.class, "EXEC-STALE-1"))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT error_summary FROM execution WHERE id=?", String.class, "EXEC-STALE-1"))
                .contains("卡死");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM execution WHERE id=?", String.class, "EXEC-FRESH-1"))
                .isEqualTo("RUNNING");

        // 把新鲜那条也终结后，不应再有任何 RUNNING 阻塞该流程
        jdbc.update("UPDATE execution SET status='SUCCESS' WHERE id=?", "EXEC-FRESH-1");
        assertThat(executionService.hasRunning(id)).isFalse();
    }

    /** 连接池保活参数必须从 application.yml 正确绑定到 Hikari，否则 PG 空闲连接会被网络回收。 */
    @Test
    void 连接池保活参数已生效() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getKeepaliveTime()).as("keepaliveTime").isBetween(30_000L, 60_000L);
        assertThat(hikari.getMaxLifetime()).as("maxLifetime").isBetween(120_000L, 600_000L);
        assertThat(hikari.getMaxLifetime()).as("maxLifetime").isGreaterThan(hikari.getKeepaliveTime());
        assertThat(hikari.getMinimumIdle()).as("minimumIdle").isGreaterThanOrEqualTo(1);
        assertThat(hikari.getValidationTimeout()).as("validationTimeout").isGreaterThan(0);
    }

    /** 正确口令可换到令牌；不带令牌访问受保护接口返回 401。 */
    @Test
    void 登录鉴权() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mvc.perform(get("/api/v1/projects"))
                .andExpect(status().isUnauthorized());
    }

    /** 节点定义接口免登录可访问，且必须下发 20+ 个内置节点。 */
    @Test
    void 节点定义下发() throws Exception {
        mvc.perform(get("/api/v1/nodes/definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(20)))
                .andExpect(jsonPath("$.data[0].schema.fields").isArray());
    }

    /** 手动执行完整链路：建流程 → 运行 → 轮询成功 → 有日志 → 看板更新。 */
    @Test
    void 手动执行生成日志与统计数据() throws Exception {
        String token = login();
        String graph = """
                {
                  "schemaVersion": 1,
                  "settings": {},
                  "nodes": [
                    {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"手动触发",
                     "position":{"x":60,"y":120},"config":{}},
                    {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"加工",
                     "position":{"x":360,"y":120},"config":{"code":"return [{ok: true, count: 3}]"},"config2":{}},
                    {"id":"n3","type":"output","subtype":"alert","nodeVersion":"1","title":"告警",
                     "position":{"x":660,"y":120},"config":{"channel":"企业微信"}}
                  ],
                  "connections": [
                    {"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"},
                    {"id":"e2","source":"n2","target":"n3","sourceHandle":"out","targetHandle":"in"}
                  ]
                }
                """;

        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "集成测试流程", "projectId", "P-001", "graph", json.readTree(graph)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String flowId = read(created).path("data").path("id").asText();

        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String executionId = read(run).path("data").path("id").asText();
        assertThat(executionId).startsWith("EXEC");

        // 轮询等待终态（执行是异步的）
        String status = "";
        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            status = read(detail).path("data").path("status").asText();
            if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(status).isEqualTo("SUCCESS");

        mvc.perform(get("/api/v1/executions/" + executionId + "/logs")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/executions/" + executionId + "/nodes")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc.perform(get("/api/v1/dashboard/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.flowCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.totalRecords").isNumber());
    }

    /** 数据源只有一张类型表单：连接与认证字段不重复，类别/协议由类型推导，密码只回掩码。 */
    @Test
    void 数据源按类型统一填写连接与认证() throws Exception {
        String token = login();
        MvcResult types = mvc.perform(get("/api/v1/datasources/credential-types")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(13)))
                .andReturn();
        String body = read(types).path("data").toString();
        assertTrue(body.contains("oauth2") && body.contains("http"));
        assertTrue(body.contains("opcua") && body.contains("modbus"),
                "OPC UA / Modbus 数据源类型应对外暴露");
        assertTrue(body.contains("kingdee") && body.contains("feishu") && body.contains("database"));

        MvcResult feishu = mvc.perform(post("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试飞书\","
                                + "\"credentialType\":\"feishu\","
                                + "\"credentialFields\":{\"appId\":\"cli_test\",\"appSecret\":\"secret-123\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.category").value("IT"))
                .andExpect(jsonPath("$.data.protocol").value("HTTP/REST"))
                .andExpect(jsonPath("$.data.credentialFields.appId").value("cli_test"))
                .andExpect(jsonPath("$.data.credentialFields.appSecret").value("******"))
                .andReturn();
        String feishuId = read(feishu).path("data").path("id").asText();

        MvcResult db = mvc.perform(post("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试数据库\","
                                + "\"credentialType\":\"database\","
                                + "\"credentialFields\":{\"driver\":\"PostgreSQL\",\"host\":\"127.0.0.1\","
                                + "\"port\":\"5432\",\"database\":\"demo\",\"username\":\"u\",\"password\":\"p\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.protocol").value("PostgreSQL"))
                .andReturn();
        assertTrue(read(db).path("data").path("credentialFields").toString().contains("127.0.0.1"));

        // 密码字段真实解密后可供执行器使用；连接字段由同一处解析出 JDBC URL
        String stored = jdbc.queryForObject(
                "SELECT credential_json FROM datasource WHERE id=?", String.class, feishuId);
        assertTrue(stored.contains("v1:"));
    }

    /** 变量明文返回，并按项目作用域覆盖：项目变量优先级高于全部项目变量。 */
    @Test
    void 变量明文返回且按项目隔离() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/variables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"全局标记\",\"key\":\"SCOPE_KEY\",\"value\":\"global\",\"scope\":\"all\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/variables")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"车间A标记\",\"key\":\"SCOPE_KEY\",\"value\":\"p1\",\"scope\":\"P-001\"}"))
                .andExpect(status().isOk());

        MvcResult vars = mvc.perform(get("/api/v1/variables")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
                .andReturn();
        assertTrue(read(vars).path("data").toString().contains("\"value\":\"global\""));

        String graph = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[
                   {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                   {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"读变量","position":{"x":200,"y":0},
                    "config":{"code":"return [{ value: $vars.SCOPE_KEY || 'none' }]"}}],
                 "connections":[{"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"}]}
                """;
        String p1 = createAndRunFlow(token, "项目A变量流程", "P-001", graph);
        String p2 = createAndRunFlow(token, "项目B变量流程", "P-002", graph);
        assertThat(nodeOutput(token, p1)).contains("p1");
        assertThat(nodeOutput(token, p2)).contains("global");
    }

    /** 创建并执行一条流程，返回执行 ID。 */
    private String createAndRunFlow(String token, String name, String projectId, String graph) throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", name, "projectId", projectId, "graph", json.readTree(graph)))))
                .andExpect(status().isOk()).andReturn();
        String flowId = read(created).path("data").path("id").asText();
        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        return read(run).path("data").path("id").asText();
    }

    /** 读取一次执行中代码节点的输出文本。 */
    private String nodeOutput(String token, String executionId) throws Exception {
        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                    .header("Authorization", "Bearer " + token)).andReturn();
            String status = read(detail).path("data").path("status").asText();
            if ("SUCCESS".equals(status)) break;
            Thread.sleep(100);
        }
        MvcResult nodes = mvc.perform(get("/api/v1/executions/" + executionId + "/nodes")
                .header("Authorization", "Bearer " + token)).andReturn();
        return read(nodes).path("data").toString();
    }

    /** 手动运行必须执行当前草稿：发布快照里没有延时节点，草稿里新增后应立刻生效。 */
    @Test
    void 手动运行执行草稿图而不是发布快照() throws Exception {
        String token = login();
        String baseGraph = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[
                   {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                   {"id":"n2","type":"output","subtype":"alert","nodeVersion":"1","title":"告警","position":{"x":200,"y":0},"config":{}}
                 ],
                 "connections":[{"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"}]}
                """;
        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "草稿运行测试", "projectId", "P-001", "graph", json.readTree(baseGraph)))))
                .andExpect(status().isOk()).andReturn();
        String flowId = read(created).path("data").path("id").asText();
        mvc.perform(post("/api/v1/flows/" + flowId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        String draftGraph = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[
                   {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                   {"id":"n2","type":"logic","subtype":"delay","nodeVersion":"1","title":"等待","position":{"x":200,"y":0},"config":{"duration":5,"unit":"分钟"}},
                   {"id":"n3","type":"output","subtype":"alert","nodeVersion":"1","title":"告警","position":{"x":400,"y":0},"config":{}}
                 ],
                 "connections":[
                   {"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"},
                   {"id":"e2","source":"n2","target":"n3","sourceHandle":"out","targetHandle":"in"}
                 ]}
                """;
        mvc.perform(put("/api/v1/flows/" + flowId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "草稿运行测试", "projectId", "P-001", "graph", json.readTree(draftGraph)))))
                .andExpect(status().isOk());

        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = read(run).path("data").path("id").asText();
        // 日志页应展示本次实际运行的草稿图（含新增的 delay 节点），而不是发布快照
        String savedGraph = jdbc.queryForObject(
                "SELECT graph_json FROM execution_graph WHERE execution_id=?", String.class, executionId);
        assertThat(savedGraph).contains("\"delay\"");
        String status = "";
        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                    .header("Authorization", "Bearer " + token)).andReturn();
            status = read(detail).path("data").path("status").asText();
            if ("WAITING".equals(status) || "SUCCESS".equals(status) || "FAILED".equals(status)) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("WAITING");

        jdbc.update("UPDATE execution_wait SET wait_until=? WHERE execution_id=?",
                "2020-01-01T00:00:00", executionId);
        executionService.resumeDue();
    }

    /** 执行记录分页：标签即备注，按文本子串过滤；并返回本次执行实际使用的图快照。 */
    @Test
    void 执行记录分页支持备注子串过滤并返回图快照() throws Exception {
        String token = login();
        String graph = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[
                   {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                   {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"输出","position":{"x":200,"y":0},
                    "config":{"code":"return [{ok:1}]"}}],
                 "connections":[{"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"}]}
                """;
        String executionId = createAndRunFlow(token, "日志页测试", "P-001", graph);
        nodeOutput(token, executionId);

        mvc.perform(post("/api/v1/executions/" + executionId + "/tags")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"生产环境告警\",\"release-2026\"]}"))
                .andExpect(status().isOk());

        for (String q : new String[]{"生产环境告警", "环境告警", "release"}) {
            JsonNode data = read(mvc.perform(get("/api/v1/executions/page")
                            .param("tag", q).param("size", "20")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn()).path("data");
            assertThat(data.path("total").asLong()).isGreaterThanOrEqualTo(1);
            assertThat(data.path("items").toString()).contains(executionId);
        }
        JsonNode miss = read(mvc.perform(get("/api/v1/executions/page")
                        .param("tag", "不存在的备注xyz")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(miss.path("total").asLong()).isZero();

        // 用命中查询取图快照
        String graphJson = "";
        JsonNode hit = read(mvc.perform(get("/api/v1/executions/page")
                        .param("tag", "release").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        for (JsonNode item : hit.path("items")) {
            if (executionId.equals(item.path("id").asText())) graphJson = item.path("graphJson").asText();
        }
        assertThat(graphJson).contains("nodejs");
    }

    /** 执行时间范围过滤：起止时间精确到秒，包含边界。 */
    @Test
    void 执行记录按时间范围过滤() throws Exception {
        String token = login();
        String graph = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[{"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}}],
                 "connections":[]}
                """;
        String executionId = createAndRunFlow(token, "时间过滤测试", "P-001", graph);
        nodeOutput(token, executionId);
        String started = jdbc.queryForObject("SELECT start_time FROM execution WHERE id=?", String.class, executionId);

        JsonNode hit = read(mvc.perform(get("/api/v1/executions/page")
                        .param("timeFrom", started).param("timeTo", started)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(hit.path("total").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(hit.path("items").toString()).contains(executionId);

        JsonNode miss = read(mvc.perform(get("/api/v1/executions/page")
                        .param("timeFrom", "2030-01-01 00:00:00")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(miss.path("total").asLong()).isZero();
    }

    /** 历史执行（无图快照的老记录）按 flowVersion 锁定当时的发布图，发布新版本不影响。 */
    @Test
    void 历史执行流程图按版本锁定() throws Exception {
        String token = login();
        String v1 = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[{"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"版本一节点","position":{"x":0,"y":0},"config":{}}],
                 "connections":[]}
                """;
        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "版本锁定测试", "projectId", "P-001", "graph", json.readTree(v1)))))
                .andExpect(status().isOk()).andReturn();
        String flowId = read(created).path("data").path("id").asText();
        mvc.perform(post("/api/v1/flows/" + flowId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = read(run).path("data").path("id").asText();
        nodeOutput(token, executionId);
        // 模拟历史记录：没有 execution_graph 快照
        jdbc.update("DELETE FROM execution_graph WHERE execution_id=?", executionId);

        String v2 = """
                {"schemaVersion":1,"settings":{},
                 "nodes":[{"id":"n2","type":"trigger","subtype":"manual","nodeVersion":"1","title":"版本二节点","position":{"x":0,"y":0},"config":{}}],
                 "connections":[]}
                """;
        mvc.perform(put("/api/v1/flows/" + flowId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "版本锁定测试", "projectId", "P-001", "graph", json.readTree(v2)))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/flows/" + flowId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 查全量页并找到目标执行
        String graphJson = "";
        for (int p = 1; p <= 20 && graphJson.isEmpty(); p++) {
            JsonNode data = read(mvc.perform(get("/api/v1/executions/page")
                            .param("page", String.valueOf(p)).param("size", "10")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andReturn()).path("data");
            for (JsonNode item : data.path("items")) {
                if (executionId.equals(item.path("id").asText())) {
                    graphJson = item.path("graphJson").asText();
                }
            }
        }
        assertThat(graphJson).contains("版本一节点");
        assertThat(graphJson).doesNotContain("版本二节点");
    }

    /** 条件分支的 true / false 必须从各自端口把不同数据送到不同下游节点。 */
    @Test
    void 条件分支按端口分流到不同节点() throws Exception {
        String token = login();
        String graph = """
                {
                  "schemaVersion": 1, "settings": {},
                  "nodes": [
                    {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                    {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"数据","position":{"x":200,"y":0},
                     "config":{"code":"return [{temp:90},{temp:10}];"}},
                    {"id":"n3","type":"logic","subtype":"condition","nodeVersion":"1","title":"分支","position":{"x":400,"y":0},
                     "config":{"expression":"{{ $json.temp > 50 }}"}},
                    {"id":"n4","type":"output","subtype":"alert","nodeVersion":"1","title":"高温","position":{"x":600,"y":0},"config":{}},
                    {"id":"n5","type":"output","subtype":"alert","nodeVersion":"1","title":"常温","position":{"x":600,"y":200},"config":{}}
                  ],
                  "connections": [
                    {"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"},
                    {"id":"e2","source":"n2","target":"n3","sourceHandle":"out","targetHandle":"in"},
                    {"id":"e3","source":"n3","target":"n4","sourceHandle":"true","targetHandle":"in"},
                    {"id":"e4","source":"n3","target":"n5","sourceHandle":"false","targetHandle":"in"}
                  ]
                }
                """;
        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "分支测试", "projectId", "P-001", "graph", json.readTree(graph)))))
                .andExpect(status().isOk()).andReturn();
        String flowId = read(created).path("data").path("id").asText();
        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = read(run).path("data").path("id").asText();

        String status = "";
        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                    .header("Authorization", "Bearer " + token)).andReturn();
            status = read(detail).path("data").path("status").asText();
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("SUCCESS");

        MvcResult nodes = mvc.perform(get("/api/v1/executions/" + executionId + "/nodes")
                .header("Authorization", "Bearer " + token)).andReturn();
        JsonNode timeline = read(nodes).path("data");
        JsonNode hot = null;
        JsonNode normal = null;
        for (JsonNode row : timeline) {
            if ("n4".equals(row.path("nodeId").asText())) hot = row;
            if ("n5".equals(row.path("nodeId").asText())) normal = row;
        }
        assertThat(hot).isNotNull();
        assertThat(normal).isNotNull();
        String hotInput = hot.path("inputJson").asText();
        String normalInput = normal.path("inputJson").asText();
        // true 分支只拿到 90，false 分支只拿到 10，两条线互不串线
        assertThat(hotInput).contains("90").doesNotContain("10");
        assertThat(normalInput).contains("10").doesNotContain("90");
    }

    /** 延时节点必须异步暂停、到期恢复，并把输入原样放行给下游。 */
    @Test
    void 延时节点暂停恢复并透传输入() throws Exception {
        String token = login();
        String graph = """
                {
                  "schemaVersion": 1, "settings": {},
                  "nodes": [
                    {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发","position":{"x":0,"y":0},"config":{}},
                    {"id":"n2","type":"code","subtype":"nodejs","nodeVersion":"1","title":"数据","position":{"x":200,"y":0},"config":{"code":"return [{temp:90, device:'PLC-1'}];"}},
                    {"id":"n3","type":"logic","subtype":"delay","nodeVersion":"1","title":"等待","position":{"x":400,"y":0},"config":{"duration":5,"unit":"分钟"}},
                    {"id":"n4","type":"output","subtype":"alert","nodeVersion":"1","title":"告警","position":{"x":600,"y":0},"config":{}}
                  ],
                  "connections": [
                    {"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"},
                    {"id":"e2","source":"n2","target":"n3","sourceHandle":"out","targetHandle":"in"},
                    {"id":"e3","source":"n3","target":"n4","sourceHandle":"out","targetHandle":"in"}
                  ]
                }
                """;
        MvcResult created = mvc.perform(post("/api/v1/flows")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "延时测试流程", "projectId", "P-001", "graph", json.readTree(graph)))))
                .andExpect(status().isOk()).andReturn();
        String flowId = read(created).path("data").path("id").asText();
        MvcResult run = mvc.perform(post("/api/v1/flows/" + flowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        String executionId = read(run).path("data").path("id").asText();

        String status = "";
        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                    .header("Authorization", "Bearer " + token)).andReturn();
            status = read(detail).path("data").path("status").asText();
            if ("WAITING".equals(status)) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("WAITING");

        // 把到期时间改到过去，再手动触发一次恢复扫描
        jdbc.update("UPDATE execution_wait SET wait_until=? WHERE execution_id=?",
                "2020-01-01T00:00:00", executionId);
        executionService.resumeDue();

        for (int i = 0; i < 40; i++) {
            MvcResult detail = mvc.perform(get("/api/v1/executions/" + executionId)
                    .header("Authorization", "Bearer " + token)).andReturn();
            status = read(detail).path("data").path("status").asText();
            if ("SUCCESS".equals(status)) break;
            Thread.sleep(100);
        }
        assertThat(status).isEqualTo("SUCCESS");

        // 延时节点有输入也有输出：恢复后必须把原数据交给下游，而不是空列表
        MvcResult nodes = mvc.perform(get("/api/v1/executions/" + executionId + "/nodes")
                .header("Authorization", "Bearer " + token)).andReturn();
        JsonNode timeline = read(nodes).path("data");
        String delayOutput = null;
        String downstreamInput = null;
        for (JsonNode row : timeline) {
            if ("n3".equals(row.path("nodeId").asText())) delayOutput = row.path("outputJson").asText();
            if ("n4".equals(row.path("nodeId").asText())) downstreamInput = row.path("inputJson").asText();
        }
        assertThat(delayOutput).contains("PLC-1").contains("90");
        assertThat(downstreamInput).isEqualTo(delayOutput);
    }

    /** AI 生成流程必须返回可直接渲染、可落库的结构化图。 */
    @Test
    void AI生成结构化流程() throws Exception {
        String token = login();

        MvcResult result = mvc.perform(post("/api/v1/ai/generate-flow")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"每 10 分钟读取 OPC UA 温度，超过 85 度推送飞书告警\","
                                + "\"projectId\":\"P-001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.explanation").isNotEmpty())
                .andReturn();

        JsonNode flow = read(result).path("data").path("flow");
        assertThat(flow.path("nodes").size()).isGreaterThanOrEqualTo(2);
        assertThat(flow.path("connections").size()).isGreaterThanOrEqualTo(1);
        assertThat(flow.path("id").asText()).startsWith("FLOW-");
        // 生成的节点必须都是已注册的类型，否则保存时会被校验拦下
        List<String> types = flow.path("nodes").findValuesAsText("type");
        assertThat(types).allMatch(t -> List.of("trigger", "source", "code", "logic", "output").contains(t));
    }

    /** AI 设置：默认未配置时用本地规则引擎，设置列表含四家厂商预设。 */
    @Test
    void AI设置默认回退本地并给出预设() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/ai/settings/reset")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.activeProvider").value("local"))
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("presets").size()).isGreaterThanOrEqualTo(5);
        List<String> keys = data.path("presets").findValuesAsText("key");
        assertThat(keys).contains("deepseek", "zhipu", "qwen", "hunyuan", "local");
        // 每个厂商都带常用模型列表和官方文档入口，模型更新由文档兜底
        for (JsonNode preset : data.path("presets")) {
            if (List.of("deepseek", "zhipu", "qwen", "hunyuan").contains(preset.path("key").asText())) {
                assertThat(preset.path("models").size()).isGreaterThan(0);
                assertThat(preset.path("docsUrl").asText()).startsWith("https://");
            }
        }
        assertThat(data.path("configs").isObject()).isTrue();
    }

    /** AI 设置：保存后密钥只回显掩码，生效来源为 settings；清空后回到本地。 */
    @Test
    void AI设置保存后掩码回显且即时生效() throws Exception {
        String token = login();
        String body = """
                {"provider":"deepseek","endpoint":"https://api.deepseek.com/v1/chat/completions",
                 "model":"deepseek-chat","apiKey":"sk-test-secret-key-123456"}
                """;
        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult result = mvc.perform(get("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("deepseek"))
                .andExpect(jsonPath("$.data.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.data.active").value(true))
                .andExpect(jsonPath("$.data.activeSource").value("settings"))
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("apiKeyMask").asText()).contains("******");
        assertThat(data.path("apiKeyMask").asText()).doesNotContain("sk-test-secret-key-123456");
        // 数据库里只存密文，不含明文
        String cipher = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id='deepseek'", String.class);
        assertThat(cipher).startsWith("v1:");
        assertThat(cipher).doesNotContain("sk-test-secret-key");

        // 清空后恢复本地引擎，避免影响后续用例
        mvc.perform(post("/api/v1/ai/settings/reset")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.active").value(false));
    }

    /** AI 设置：提交掩码占位符表示不修改密钥，原密钥保持不变。 */
    @Test
    void AI设置掩码占位符保留原密钥() throws Exception {
        String token = login();
        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"qwen\",\"endpoint\":\"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions\","
                                + "\"model\":\"qwen-plus\",\"apiKey\":\"sk-original-key-abcdef\"}"))
                .andExpect(status().isOk());
        String beforeCipher = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id='qwen'", String.class);

        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"qwen\",\"endpoint\":\"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions\","
                                + "\"model\":\"qwen-turbo\",\"apiKey\":\"******\"}"))
                .andExpect(status().isOk());
        String afterCipher = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id='qwen'", String.class);
        // AES-GCM 每次加密 IV 不同，密文必然不同；要验证的是明文没被掩码覆盖
        assertThat(cryptoService.decrypt(afterCipher)).isEqualTo("sk-original-key-abcdef");
        assertThat(cryptoService.decrypt(beforeCipher)).isEqualTo("sk-original-key-abcdef");

        mvc.perform(post("/api/v1/ai/settings/reset")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** AI 设置：各厂商密钥独立保存与回显，切换厂商不会串用，掩码占位符只保留本厂商密钥。 */
    @Test
    void AI设置各厂商密钥互不影响() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/ai/settings/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"endpoint\":\"https://api.deepseek.com/v1/chat/completions\","
                                + "\"model\":\"deepseek-chat\",\"apiKey\":\"sk-deepseek-secret-aaa\"}"))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"zhipu\",\"endpoint\":\"https://open.bigmodel.cn/api/paas/v4/chat/completions\","
                                + "\"model\":\"glm-4-flash\",\"apiKey\":\"sk-zhipu-secret-bbb\"}"))
                .andExpect(status().isOk());

        MvcResult result = mvc.perform(get("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeProvider").value("zhipu"))
                .andReturn();
        JsonNode configs = read(result).path("data").path("configs");
        assertThat(configs.path("deepseek").path("apiKeyMask").asText()).isEqualTo("sk-******aaa");
        assertThat(configs.path("zhipu").path("apiKeyMask").asText()).isEqualTo("sk-******bbb");
        assertThat(configs.path("deepseek").path("model").asText()).isEqualTo("deepseek-chat");
        assertThat(configs.path("zhipu").path("model").asText()).isEqualTo("glm-4-flash");
        // 顶层展示的是当前生效厂商，不能把另一家密钥带出来
        assertThat(read(result).path("data").path("apiKeyMask").asText()).isEqualTo("sk-******bbb");

        // 只改 deepseek 的模型名，密钥留空，不应改掉它的 Key，更不能动 zhipu 的 Key
        mvc.perform(put("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"endpoint\":\"https://api.deepseek.com/v1/chat/completions\","
                                + "\"model\":\"deepseek-reasoner\",\"apiKey\":\"******\"}"))
                .andExpect(status().isOk());
        String deepseekCipher = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id='deepseek'", String.class);
        String zhipuCipher = jdbc.queryForObject("SELECT api_key_cipher FROM ai_settings WHERE id='zhipu'", String.class);
        assertThat(cryptoService.decrypt(deepseekCipher)).isEqualTo("sk-deepseek-secret-aaa");
        assertThat(cryptoService.decrypt(zhipuCipher)).isEqualTo("sk-zhipu-secret-bbb");

        mvc.perform(post("/api/v1/ai/settings/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult cleared = mvc.perform(get("/api/v1/ai/settings")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andReturn();
        assertThat(read(cleared).path("data").path("configs").size()).isZero();
    }

    /** AI 设置生效链路：保存设置后生成流程改走大模型；清空后回退本地规则引擎。 */
    @Test
    void AI设置切换大模型并可回退() throws Exception {
        // 本地模拟 OpenAI 兼容接口，返回一个可被解析为流程的最小 JSON
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 故意返回问题场景：email/weather 两个平台不存在的 subtype，且 connections 为空。
        // 规范化后应变成合法节点（alert/http）并按顺序补齐连线。
        String flowJson = """
                {"schemaVersion":1,"name":"Weather Email Alert","nodes":[
                 {"id":"a","type":"trigger","subtype":"schedule","title":"Schedule","position":{"x":60,"y":140},"config":{}},
                 {"id":"b","type":"source","subtype":"weather","title":"Fetch Weather","position":{"x":350,"y":140},"config":{}},
                 {"id":"c","type":"output","subtype":"email","title":"Send Email","position":{"x":640,"y":140},"config":{}}
                ],"connections":[]}
                """;
        String reply = json.writeValueAsString(Map.of("choices",
                List.of(Map.of("message", Map.of("content", flowJson)))));
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String token = login();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            mvc.perform(put("/api/v1/ai/settings")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"custom\",\"endpoint\":\"" + endpoint
                                    + "\",\"model\":\"mock-model\",\"apiKey\":\"sk-mock\"}"))
                    .andExpect(status().isOk());

            MvcResult llm = mvc.perform(post("/api/v1/ai/generate-flow")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"prompt\":\"生成一个流程\",\"projectId\":\"P-001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.source").value("llm"))
                    .andExpect(jsonPath("$.data.provider").value("custom"))
                    .andReturn();
            JsonNode generated = read(llm).path("data").path("flow");
            assertThat(generated.path("nodes").size()).isEqualTo(3);
            // 非法的 weather/email 必须被纠正为平台真实存在的 subtype
            List<String> subtypes = generated.path("nodes").findValuesAsText("subtype");
            assertThat(subtypes).doesNotContain("weather", "email");
            JsonNode definitions = read(mvc.perform(get("/api/v1/nodes/definitions")).andReturn());
            Set<String> knownSubtypes = new HashSet<>();
            definitions.path("data").forEach(d -> knownSubtypes.add(d.path("subtype").asText()));
            assertThat(knownSubtypes).containsAll(subtypes);
            // 模型没给连线时，后端应按顺序补齐，保证节点间有连线且端点存在
            JsonNode connections = generated.path("connections");
            assertThat(connections.size()).isGreaterThanOrEqualTo(2);
            Set<String> nodeIds = new HashSet<>();
            generated.path("nodes").forEach(n -> nodeIds.add(n.path("id").asText()));
            for (JsonNode edge : connections) {
                assertThat(nodeIds).contains(edge.path("source").asText(), edge.path("target").asText());
            }
            // 模型给出英文流程名与英文节点标题时，后端统一换成中文展示名
            assertThat(generated.path("name").asText()).contains("生成一个流程");
            List<String> titles = generated.path("nodes").findValuesAsText("title");
            assertThat(titles).doesNotContain("Schedule", "Fetch Weather", "Send Email");
            assertThat(titles).allMatch(t -> t.matches(".*[\\u4e00-\\u9fff].*"));

            mvc.perform(post("/api/v1/ai/settings/reset")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
            MvcResult local = mvc.perform(post("/api/v1/ai/generate-flow")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"prompt\":\"每 10 分钟读取 OPC UA 温度\",\"projectId\":\"P-001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.source").value("local-template"))
                    .andReturn();
            assertThat(read(local).path("data").path("flow").path("nodes").size()).isGreaterThanOrEqualTo(2);
        } finally {
            server.stop(0);
            mvc.perform(post("/api/v1/ai/settings/reset")
                            .header("Authorization", "Bearer " + token));
        }
    }

    /**
     * SSE（AI 流式问答）结束时容器发起的 ASYNC 二次分发不应被鉴权拦截。
     *
     * <p>修复前：SecurityConfig 只放行 FORWARD/ERROR，ASYNC 分发会落到
     * anyRequest().authenticated()，而此时 SecurityContext 为空 → 抛
     * AuthorizationDeniedException。这里不带令牌模拟该分发，断言放行；
     * 同时断言普通 REQUEST 分发仍需令牌，确保鉴权未被削弱。</p>
     */
    @Test
    void SSE异步分发不被授权过滤器拦截() throws Exception {
        mvc.perform(post("/api/v1/ai/ask")
                        .with(request -> {
                            request.setDispatcherType(DispatcherType.ASYNC);
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\"}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/ai/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"你好\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** AI 诊断：失败节点展示画布名称，而不是 n1 这类节点编码。 */
    @Test
    void AI诊断失败节点显示名称而非编码() throws Exception {
        String token = login();
        mvc.perform(post("/api/v1/ai/settings/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String executionId = "EXEC-AI-DIAG-" + System.nanoTime();
        jdbc.update("INSERT INTO execution(id,flow_id,status,start_time,error_summary) VALUES(?,?,?,?,?)",
                executionId, "FLOW-AI-DIAG", "FAILED", "2026-01-01T00:00:00", "连接被拒绝");
        jdbc.update("INSERT INTO execution_node(id,execution_id,node_id,node_title,node_type,"
                        + "node_version,status,duration_ms,error_message,retry_count)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                "EN-AI-DIAG-" + System.nanoTime(), executionId, "n1", "采集温度", "source",
                "1", "FAILED", 10L, "ECONNREFUSED 127.0.0.1:9000", 0);

        MvcResult result = mvc.perform(post("/api/v1/ai/diagnose")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"executionId\":\"" + executionId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("local-rules"))
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("evidence").toString()).contains("采集温度").doesNotContain("n1");
        assertThat(data.path("rootCause").asText()).doesNotContain("n1");
        assertThat(data.path("steps").toString()).doesNotContain("n1");
    }

    /** 未配置大模型时，问答基于真实流程数据回答，不再使用写死的演示数字。 */
    @Test
    void AI问答本地回退基于真实运行数据() throws Exception {
        String token = login();
        String name = "温度巡检上下文流程";
        insertNamedFlow("FLOW-AI-CONTEXT-" + System.nanoTime(), name);
        mvc.perform(post("/api/v1/ai/settings/reset").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        MvcResult result = mvc.perform(post("/api/v1/ai/ask")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"当前采集任务运行情况如何？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("local-rules"))
                .andReturn();
        String answer = read(result).path("data").path("answer").asText();
        assertThat(answer).contains(name).contains("流程总数");
        assertThat(answer).doesNotContain("PT101");
    }

    /** 配置模型后，问答把真实上下文放进 system 提示词，并支持流式逐段输出。 */
    @Test
    void AI问答注入运行上下文并支持流式输出() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"运行\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"正常\"}}]}\n\n"
                + "data: [DONE]\n\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            captured.set(requestBody);
            boolean stream = requestBody.contains("\"stream\":true");
            String response = stream ? sse : json.writeValueAsString(Map.of("choices",
                    List.of(Map.of("message", Map.of("content", "上下文已收到")))));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    stream ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String token = login();
        String name = "上下文注入验证流程";
        insertNamedFlow("FLOW-AI-PROMPT-" + System.nanoTime(), name);
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
            mvc.perform(put("/api/v1/ai/settings")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"provider\":\"custom\",\"endpoint\":\"" + endpoint
                                    + "\",\"model\":\"mock-model\",\"apiKey\":\"sk-mock\"}"))
                    .andExpect(status().isOk());

            MvcResult answer = mvc.perform(post("/api/v1/ai/ask")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"当前采集任务运行情况如何？\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.source").value("llm"))
                    .andExpect(jsonPath("$.data.answer").value("上下文已收到"))
                    .andReturn();
            assertThat(answer).isNotNull();
            String sent = captured.get();
            assertThat(sent).contains("\"role\":\"system\"").contains("流程总数").contains(name);

            List<String> chunks = new ArrayList<>();
            Map<String, Object> meta = aiService.askStream("当前采集任务运行情况如何？", chunks::add);
            assertThat(String.join("", chunks)).isEqualTo("运行正常");
            assertThat(meta).containsEntry("source", "llm");
        } finally {
            server.stop(0);
            mvc.perform(post("/api/v1/ai/settings/reset").header("Authorization", "Bearer " + token));
        }
    }

    /** 系统只保留 admin：历史演示角色不能登录，用户表里也不应存在。 */
    @Test
    void 仅保留管理员账号() throws Exception {
        for (String username : List.of("engineer", "viewer")) {
            mvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"whatever\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class)).isEqualTo(1L);
    }

    /** 数据库连接类型：保存前可测试；无服务时给出明确失败原因，而不是空 URL。 */
    @Test
    void 数据库连接测试给出明确结果() throws Exception {
        String token = login();
        MvcResult draft = mvc.perform(post("/api/v1/datasources/test")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试库\","
                                + "\"credentialType\":\"database\","
                                + "\"credentialFields\":{\"driver\":\"MySQL\",\"host\":\"127.0.0.1\","
                                + "\"port\":\"1\",\"database\":\"demo\",\"username\":\"u\",\"password\":\"p\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andReturn();
        assertThat(read(draft).path("data").path("message").asText())
                .contains("JDBC 连接失败");

        // 只给主机与账号、不填数据库名：MySQL 允许连接实例，不能被参数校验拦下（回归用例）
        MvcResult noDatabase = mvc.perform(post("/api/v1/datasources/test")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"无库名测试\","
                                + "\"credentialType\":\"database\","
                                + "\"credentialFields\":{\"driver\":\"MySQL\",\"host\":\"127.0.0.1\","
                                + "\"port\":\"1\",\"username\":\"u\",\"password\":\"p\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andReturn();
        assertThat(read(noDatabase).path("data").path("message").asText())
                .contains("JDBC 连接失败");

        MvcResult created = mvc.perform(post("/api/v1/datasources")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试库2\","
                                + "\"credentialType\":\"database\","
                                + "\"credentialFields\":{\"driver\":\"MySQL\","
                                + "\"jdbcUrl\":\"jdbc:mysql://127.0.0.1:1/demo?connectTimeout=100\","
                                + "\"username\":\"u\",\"password\":\"p\"}}"))
                .andExpect(status().isOk())
                .andReturn();
        String id = read(created).path("data").path("id").asText();
        MvcResult tested = mvc.perform(post("/api/v1/datasources/" + id + "/test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(false))
                .andReturn();
        assertThat(read(tested).path("data").path("message").asText()).contains("JDBC 连接失败");
    }

    /** 触发器下拉字段必须为中文说明，底层 value 保持协议默认值。 */
    @Test
    void 触发器选择字段带中文标签() throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/nodes/definitions"))
                .andExpect(status().isOk()).andReturn();
        JsonNode defs = read(result).path("data");
        JsonNode kafka = null;
        JsonNode mqtt = null;
        for (JsonNode def : defs) {
            String key = def.path("type").asText() + ":" + def.path("subtype").asText();
            if ("trigger:kafka".equals(key)) kafka = def;
            if ("trigger:mqtt".equals(key)) mqtt = def;
        }
        assertThat(kafka).isNotNull();
        assertThat(mqtt).isNotNull();
        assertThat(kafka.findValuesAsText("earliest")).contains("最早位点（从头消费历史消息）");
        assertThat(mqtt.findValuesAsText("1")).contains("1 · 至少一次（可能重复）");
    }

    /** 告警节点端到端：API 建 SMTP 数据源 → 建流程 → 执行 → 本地 SMTP 真实收到邮件。 */
    @Test
    void 告警通知节点端到端真实发送邮件() throws Exception {
        String token = login();
        try (SmtpServer smtp = new SmtpServer()) {
            Map<String, Object> source = Map.of(
                    "name", "集成测试-SMTP",
                    "credentialType", "email",
                    "credentialFields", Map.of(
                            "smtpHost", "127.0.0.1",
                            "smtpPort", String.valueOf(smtp.port()),
                            "encryption", "无",
                            "username", "alert@tonglink.test",
                            "password", "test-auth-code",
                            "from", "alert@tonglink.test"));
            MvcResult createdSource = mvc.perform(post("/api/v1/datasources")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(source)))
                    .andExpect(status().isOk()).andReturn();
            String sourceId = read(createdSource).path("data").path("id").asText();
            assertThat(sourceId).startsWith("DS");

            String graph = """
                    {"schemaVersion":1,"settings":{},
                     "nodes":[
                       {"id":"n1","type":"trigger","subtype":"manual","nodeVersion":"1","title":"触发",
                        "position":{"x":0,"y":0},"config":{}},
                       {"id":"n2","type":"output","subtype":"alert","nodeVersion":"1","title":"邮件告警",
                        "position":{"x":220,"y":0},
                        "config":{"channel":"邮件","credential":"%s","webhook":"recipient@example.com",
                                  "titleTemplate":"告警 {{ $json.flowId }}",
                                  "messageTemplate":"IT-ALERT-MARKER flow={{ $json.flowId }}",
                                  "severity":"严重","suppressMinutes":0}}
                     ],
                     "connections":[{"id":"e1","source":"n1","target":"n2","sourceHandle":"out","targetHandle":"in"}]}
                    """.formatted(sourceId);
            String executionId = createAndRunFlow(token, "集成测试-邮件告警", "P-TEST", graph);
            String nodeResult = nodeOutput(token, executionId);
            assertThat(nodeResult).contains("sent").contains("real");

            assertThat(smtp.messages).hasSize(1);
            String mail = smtp.messages.get(0);
            assertThat(mail).contains("To: recipient@example.com");
            // Jakarta Mail 会按正文内容选择 base64 或 quoted-printable，两者中标记均为可读文本
            assertThat(mail).contains("IT-ALERT-MARKER");
        }
    }

    /** 最小 SMTP 服务端：支持 PLAIN/LOGIN 认证与 DATA，供端到端邮件断言使用。 */
    private static final class SmtpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        SmtpServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = Thread.ofPlatform().daemon(true).start(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (running) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException e) {
                    if (running) throw new RuntimeException(e);
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();
            write(out, "220 localhost ESMTP\r\n");
            String line;
            boolean data = false;
            StringBuilder message = new StringBuilder();
            while ((line = in.readLine()) != null) {
                if (data) {
                    if (".".equals(line)) {
                        messages.add(message.toString());
                        write(out, "250 OK\r\n");
                        data = false;
                    } else {
                        message.append(line).append("\r\n");
                    }
                    continue;
                }
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    write(out, "250-localhost\r\n250-AUTH PLAIN LOGIN\r\n250 OK\r\n");
                } else if (command.startsWith("AUTH LOGIN")) {
                    write(out, "334 " + Base64.getEncoder().encodeToString("Username:".getBytes(StandardCharsets.UTF_8)) + "\r\n");
                    in.readLine();
                    write(out, "334 " + Base64.getEncoder().encodeToString("Password:".getBytes(StandardCharsets.UTF_8)) + "\r\n");
                    in.readLine();
                    write(out, "235 Authentication successful\r\n");
                } else if (command.startsWith("AUTH")) {
                    write(out, "235 Authentication successful\r\n");
                } else if (command.startsWith("DATA")) {
                    write(out, "354 End data with <CR><LF>.<CR><LF>\r\n");
                    data = true;
                } else if (command.startsWith("QUIT")) {
                    write(out, "221 Bye\r\n");
                    break;
                } else {
                    write(out, "250 OK\r\n");
                }
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
        }
    }

    /** 登录并返回令牌。 */
    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }
}
