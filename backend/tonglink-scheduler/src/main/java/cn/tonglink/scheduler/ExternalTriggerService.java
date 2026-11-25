/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.scheduler;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.BrokerConfigResolver;
import cn.tonglink.execution.ExecutionService;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.repository.FlowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 外部消息触发器：把已发布流程中的 Kafka / MQTT 触发节点变成长期订阅。
 *
 * <p>做法参考 n8n 的 trigger 激活机制：流程发布即启动订阅，下线或配置变更即停止/重建。
 * 每条消息都会创建一次独立执行，消息体作为触发器节点输出传给下游，
 * 因此 trigger 与 output 节点使用同一套配置和凭据解析逻辑。</p>
 */
@Service
public class ExternalTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ExternalTriggerService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final FlowRepository flows;
    private final ExecutionService executionService;
    private final BrokerConfigResolver resolver;
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    public ExternalTriggerService(FlowRepository flows, ExecutionService executionService,
                                  BrokerConfigResolver resolver) {
        this.flows = flows;
        this.executionService = executionService;
        this.resolver = resolver;
    }

    /** 周期性对齐「已发布流程中的触发节点」与「运行中的订阅」。 */
    @Scheduled(fixedDelayString = "${tonglink.scheduler.interval-ms:5000}")
    public void reconcile() {
        Set<String> desired = new HashSet<>();
        for (Flow flow : flows.flows()) {
            if (!"active".equals(flow.status()) || flow.publishedGraphJson() == null
                    || flow.publishedGraphJson().isBlank()) {
                continue;
            }
            FlowGraph graph;
            try {
                graph = JsonUtil.parse(flow.publishedGraphJson(), FlowGraph.class);
            } catch (Exception e) {
                continue;
            }
            if (graph == null || graph.nodes() == null) continue;
            for (GraphNode node : graph.nodes()) {
                if (!"trigger".equals(node.type())
                        || !("kafka".equals(node.subtype()) || "mqtt".equals(node.subtype()))) {
                    continue;
                }
                String key = flow.id() + "|" + node.id();
                desired.add(key);
                try {
                    Map<String, Object> cfg = resolver.resolve(node.config() == null ? Map.of() : node.config());
                    String signature = resolver.signature(flow.id(), node.id(), cfg);
                    Subscription existing = subscriptions.get(key);
                    if (existing != null && existing.signature().equals(signature)) {
                        continue;
                    }
                    stop(key);
                    SubscriptionHandle started = "kafka".equals(node.subtype())
                            ? startKafka(flow.id(), cfg)
                            : startMqtt(flow.id(), cfg);
                    if (started != null) {
                        subscriptions.put(key, new Subscription(signature, started));
                        log.info("已启动 {} 触发器：{}", node.subtype(), key);
                    }
                } catch (Exception e) {
                    log.warn("启动 {} 触发器失败（{}）：{}", node.subtype(), key, e.getMessage());
                }
            }
        }
        for (String key : new HashSet<>(subscriptions.keySet())) {
            if (!desired.contains(key)) {
                stop(key);
            }
        }
    }

    private void stop(String key) {
        Subscription removed = subscriptions.remove(key);
        if (removed != null) {
            removed.close();
            log.info("已停止触发器订阅：{}", key);
        }
    }

    private SubscriptionHandle startKafka(String flowId, Map<String, Object> cfg) {
        String servers = BrokerConfigResolver.bootstrapServers(cfg);
        String topic = str(cfg.get("topic"));
        if (servers.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("缺少 bootstrapServers 或 topic");
        }
        Properties props = new Properties();
        props.put("bootstrap.servers", servers);
        props.put("group.id", str(cfg.getOrDefault("groupId", "tonglink-" + flowId)));
        props.put("auto.offset.reset", str(cfg.getOrDefault("offsetReset", "latest")));
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class);
        props.put("value.deserializer", StringDeserializer.class);
        props.put("session.timeout.ms", "10000");
        props.put("request.timeout.ms", "20000");
        String security = str(cfg.get("securityProtocol"));
        if (!security.isBlank()) {
            props.put("security.protocol", security);
            if ("SASL_PLAINTEXT".equals(security) || "SASL_SSL".equals(security)) {
                props.put("sasl.mechanism", str(cfg.getOrDefault("saslMechanism", "PLAIN")));
                props.put("sasl.jaas.config",
                        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\""
                                + escape(str(cfg.get("username"))) + "\" password=\""
                                + escape(str(cfg.get("password"))) + "\";");
            }
        }
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        boolean json = !"String".equals(str(cfg.getOrDefault("deserializer", "JSON")));
        int batchSize = Math.max(1, BrokerConfigResolver.intValue(cfg, "batchSize", 10));
        Thread thread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                    if (records.isEmpty()) continue;
                    List<ConsumerRecord<String, String>> batch = new ArrayList<>();
                    Map<TopicPartition, OffsetAndMetadata> offsets = new LinkedHashMap<>();
                    for (ConsumerRecord<String, String> record : records) {
                        batch.add(record);
                        offsets.put(new TopicPartition(record.topic(), record.partition()),
                                new OffsetAndMetadata(record.offset() + 1));
                        if (batch.size() >= batchSize) {
                            publishKafkaBatch(flowId, batch, offsets, json);
                            consumer.commitSync(offsets);
                            batch.clear();
                            offsets.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        publishKafkaBatch(flowId, batch, offsets, json);
                        consumer.commitSync(offsets);
                    }
                } catch (org.apache.kafka.common.errors.WakeupException e) {
                    return;
                } catch (Exception e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        log.warn("Kafka 触发器 {} 轮询失败：{}", flowId, e.getMessage());
                    }
                }
            }
        }, "tl-kafka-trigger-" + flowId);
        thread.setDaemon(true);
        thread.start();
        return () -> {
            consumer.wakeup();
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            consumer.close();
        };
    }

    private void publishKafkaBatch(String flowId, List<ConsumerRecord<String, String>> batch,
                                   Map<TopicPartition, OffsetAndMetadata> offsets, boolean json) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ConsumerRecord<String, String> record : batch) {
            Map<String, Object> row = json ? parseJsonObject(record.value()) : new LinkedHashMap<>();
            if (!json) {
                row.put("value", record.value());
            }
            row.put("topic", record.topic());
            row.put("partition", record.partition());
            row.put("offset", record.offset());
            row.put("key", record.key());
            row.put("timestamp", record.timestamp());
            rows.add(row);
        }
        ConsumerRecord<String, String> first = batch.get(0);
        String idempotency = "kafka:" + flowId + ":" + first.topic() + ":"
                + first.partition() + ":" + first.offset();
        executionService.submit(flowId, "kafka", idempotency, rows);
    }

    private SubscriptionHandle startMqtt(String flowId, Map<String, Object> cfg) throws Exception {
        String uri = BrokerConfigResolver.mqttServerUri(cfg);
        String topic = str(cfg.get("topic"));
        if (uri.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("缺少 Broker 地址或订阅主题");
        }
        int qos = BrokerConfigResolver.intValue(cfg, "qos", 1);
        boolean json = !"原始字符串".equals(str(cfg.getOrDefault("payloadFormat", "JSON")));
        String clientId = str(cfg.get("clientId"));
        if (clientId.isBlank()) {
            clientId = "tl-trig-" + flowId.replaceAll("[^A-Za-z0-9]", "");
        }
        MqttClient client = new MqttClient(uri, clientId, new MemoryPersistence());
        MqttConnectOptions options = new MqttConnectOptions();
        options.setCleanSession(BrokerConfigResolver.boolValue(cfg, "cleanSession", true));
        options.setAutomaticReconnect(true);
        options.setConnectionTimeout(10);
        String username = str(cfg.get("username"));
        if (!username.isBlank()) {
            options.setUserName(username);
            options.setPassword(str(cfg.get("password")).toCharArray());
        }
        client.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                log.warn("MQTT 触发器 {} 连接断开：{}", flowId, cause == null ? "" : cause.getMessage());
            }

            @Override
            public void messageArrived(String arrivedTopic, MqttMessage message) {
                String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                Map<String, Object> row = json ? parseJsonObject(payload) : new LinkedHashMap<>();
                if (!json) row.put("value", payload);
                row.put("topic", arrivedTopic);
                row.put("qos", message.getQos());
                row.put("retained", message.isRetained());
                String idempotency = "mqtt:" + flowId + ":" + arrivedTopic + ":"
                        + System.nanoTime();
                executionService.submit(flowId, "mqtt", idempotency, List.of(row));
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
                // 触发器只订阅不发布
            }
        });
        client.connect(options);
        client.subscribe(topic, qos);
        return () -> {
            try {
                if (client.isConnected()) client.disconnect();
                client.close();
            } catch (Exception ignored) {
                // 关闭失败不影响流程下线，下一次对齐会重试
            }
        };
    }

    private static Map<String, Object> parseJsonObject(String payload) {
        if (payload == null || payload.isBlank()) return new LinkedHashMap<>();
        Map<String, Object> parsed = JsonUtil.parse(payload, MAP_TYPE);
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 可关闭的订阅句柄。 */
    private interface SubscriptionHandle {
        void close();
    }

    /** 订阅配置签名 + 关闭句柄。 */
    private record Subscription(String signature, SubscriptionHandle handle) {
        void close() {
            try {
                handle.close();
            } catch (Exception e) {
                log.warn("关闭触发器订阅失败：{}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void closeAll() {
        subscriptions.keySet().forEach(this::stop);
    }
}
