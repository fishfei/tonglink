/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.BrokerConfigResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Kafka 输出节点：把上游的每一行作为一条消息发送到 Topic。 */
@Component
public class KafkaOutputExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutputExecutor.class);
    private final BrokerConfigResolver resolver;

    public KafkaOutputExecutor(BrokerConfigResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Set<String> supports() {
        return Set.of("output:kafka");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        Map<String, Object> cfg = resolver.resolve(config);
        String servers = BrokerConfigResolver.bootstrapServers(cfg);
        String topic = str(cfg.get("topic"));
        if (servers.isBlank() || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka 输出节点缺少 bootstrapServers 或 topic，请检查数据源配置");
        }
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, str(cfg.getOrDefault("acks", "1")));
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 30000);
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

        String keyField = str(cfg.get("keyField"));
        boolean json = !"String".equals(str(cfg.getOrDefault("serializer", "JSON")));
        List<Map<String, Object>> results = new ArrayList<>();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (Map<String, Object> row : input) {
                String value = json ? JsonUtil.stringify(row) : String.valueOf(row.values().iterator().next());
                ProducerRecord<String, String> record = new ProducerRecord<>(topic,
                        keyField.isBlank() ? null : str(row.get(keyField)), value);
                RecordMetadata meta = producer.send(record).get(15, TimeUnit.SECONDS);
                Map<String, Object> sent = new LinkedHashMap<>();
                sent.put("topic", meta.topic());
                sent.put("partition", meta.partition());
                sent.put("offset", meta.offset());
                sent.put("payload", value);
                results.add(sent);
            }
            producer.flush();
        } catch (Exception e) {
            throw new IllegalStateException("Kafka 消息发送失败：" + e.getMessage(), e);
        }
        log.info("Kafka 输出节点发送 {} 条消息到 {}", results.size(), topic);
        return new ExecResult(results, null);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
