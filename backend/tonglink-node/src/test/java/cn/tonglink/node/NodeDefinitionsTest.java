/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.node;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.repository.FlowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 节点目录一致性：Kafka / MQTT 的 trigger 与 output 必须成对存在且配置完整。 */
class NodeDefinitionsTest {

    @Test
    @SuppressWarnings("unchecked")
    void kafkaAndMqttHaveBothTriggerAndOutputNodes() throws Exception {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource("node-definitions.json").getInputStream()) {
            root = JsonUtil.mapper().readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        Map<String, Object> schemas = (Map<String, Object>) root.get("schemas");
        for (String key : List.of("trigger:kafka", "output:kafka", "trigger:mqtt", "output:mqtt")) {
            assertTrue(schemas.containsKey(key), "缺少节点定义：" + key);
            Map<String, Object> schema = (Map<String, Object>) schemas.get(key);
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            assertTrue(fields.stream().anyMatch(f -> "topic".equals(f.get("key"))),
                    key + " 缺少 topic 配置");
            assertTrue(fields.stream().anyMatch(f -> "datasource".equals(f.get("key"))),
                    key + " 缺少数据源配置");
        }
        Map<String, Object> delay = (Map<String, Object>) schemas.get("logic:delay");
        assertTrue(delay.get("note").toString().contains("异步"));
        assertTrue(schemas.containsKey("source:opcua") && schemas.containsKey("source:modbus"),
                "OPC UA / Modbus 工业协议节点应出现在节点定义中");
        for (String key : java.util.List.of("source:opcua", "source:modbus")) {
            Map<String, Object> schema = (Map<String, Object>) schemas.get(key);
            assertTrue(schema.get("fields") instanceof List<?> fields && !fields.isEmpty(),
                    key + " 缺少字段定义");
            assertTrue(((List<?>) schema.get("fields")).stream().anyMatch(
                            f -> "operation".equals(((Map<?, ?>) f).get("key"))),
                    key + " 缺少读写操作字段");
        }

        List<Map<String, Object>> market = (List<Map<String, Object>>) root.get("market");
        assertTrue(market.stream().allMatch(m -> m.get("runtime") instanceof Map<?, ?>),
                "市场中的每个节点包都必须声明可执行运行时");
    }

    @Test
    @SuppressWarnings("unchecked")
    void kafkaAndMqttDoNotExposeSeparateCredentialSelectors() throws Exception {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource("node-definitions.json").getInputStream()) {
            root = JsonUtil.mapper().readValue(in, new TypeReference<Map<String, Object>>() {});
        }
        Map<String, Object> schemas = (Map<String, Object>) root.get("schemas");
        for (String key : List.of("trigger:kafka", "output:kafka", "trigger:mqtt", "output:mqtt")) {
            Map<String, Object> schema = (Map<String, Object>) schemas.get(key);
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            assertTrue(fields.stream().noneMatch(f -> List.of("credential", "username", "password")
                            .contains(f.get("key")) && "datasource".equals(f.get("type"))),
                    key + " 不应再单独选择凭证，请统一使用数据源");
        }
    }

    @Test
    void installRejectsManifestWithoutRuntime() {
        NodeRegistry registry = new NodeRegistry(mock(FlowRepository.class));
        assertThrows(ApiException.class, () -> registry.installManifest(
                Map.of("type", "output", "subtype", "demo", "title", "示例",
                        "schema", Map.of("title", "示例", "fields", List.of())),
                "pkg-demo"));
    }
}
