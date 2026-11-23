/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
