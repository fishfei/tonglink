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

import cn.tonglink.execution.ExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    /** 每个测试类使用唯一数据库文件，避免上次运行残留数据。 */
    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        Path db = Path.of("target", "it-" + System.nanoTime() + ".db").toAbsolutePath();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + db);
        registry.add("tonglink.scheduler.interval-ms", () -> "3600000");
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
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(11)))
                .andReturn();
        String body = read(types).path("data").toString();
        assertTrue(body.contains("opcua") && body.contains("modbus") && body.contains("http"));
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
                        "config":{"channel":"邮件","credential":"%s","webhook":"java_fish@126.com",
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
            assertThat(mail).contains("To: java_fish@126.com");
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
