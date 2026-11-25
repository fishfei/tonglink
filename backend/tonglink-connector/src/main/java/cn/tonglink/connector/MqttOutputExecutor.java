/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.BrokerConfigResolver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** MQTT 输出节点：把上游每一行发布到指定主题。 */
@Component
public class MqttOutputExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(MqttOutputExecutor.class);
    private final BrokerConfigResolver resolver;

    public MqttOutputExecutor(BrokerConfigResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Set<String> supports() {
        return Set.of("output:mqtt");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        Map<String, Object> cfg = resolver.resolve(config);
        String uri = BrokerConfigResolver.mqttServerUri(cfg);
        String topic = str(cfg.get("topic"));
        if (uri.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("MQTT 输出节点缺少 Broker 地址或主题，请检查数据源配置");
        }
        int qos = BrokerConfigResolver.intValue(cfg, "qos", 1);
        boolean json = !"原始字符串".equals(str(cfg.getOrDefault("payloadFormat", "JSON")));
        String clientId = str(cfg.get("clientId"));
        if (clientId.isBlank()) {
            clientId = "tl-out-" + IdUtil.next();
        }

        List<Map<String, Object>> results = new ArrayList<>();
        MqttClient client = null;
        try {
            client = new MqttClient(uri, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(BrokerConfigResolver.boolValue(cfg, "cleanSession", true));
            options.setAutomaticReconnect(false);
            options.setConnectionTimeout(10);
            String username = str(cfg.get("username"));
            if (!username.isBlank()) {
                options.setUserName(username);
                options.setPassword(str(cfg.get("password")).toCharArray());
            }
            client.connect(options);
            for (Map<String, Object> row : input) {
                String payload = json ? JsonUtil.stringify(row)
                        : String.valueOf(row.values().iterator().next());
                MqttMessage message = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                message.setQos(qos);
                message.setRetained(BrokerConfigResolver.boolValue(cfg, "retained", false));
                client.publish(topic, message);
                Map<String, Object> sent = new LinkedHashMap<>();
                sent.put("topic", topic);
                sent.put("qos", qos);
                sent.put("payload", payload);
                results.add(sent);
            }
        } catch (Exception e) {
            throw new IllegalStateException("MQTT 消息发布失败：" + e.getMessage(), e);
        } finally {
            if (client != null) {
                try {
                    if (client.isConnected()) client.disconnect();
                    client.close();
                } catch (Exception ignored) {
                    // 关闭失败不影响已经发布的消息
                }
            }
        }
        log.info("MQTT 输出节点发布 {} 条消息到 {}", results.size(), topic);
        return new ExecResult(results, null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
