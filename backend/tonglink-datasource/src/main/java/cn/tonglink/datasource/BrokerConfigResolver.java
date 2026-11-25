/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import cn.tonglink.common.JsonUtil;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 把节点配置解析成可直接连接消息中间件的参数。
 *
 * <p>节点里既可以引用数据源（推荐），也可以直接写 broker 地址；凭据字段填的是凭据 ID，
 * 这里统一解密并展开。Kafka / MQTT 的输出节点和触发器共用这套解析逻辑，
 * 保证两处配置语义完全一致。</p>
 */
@Component
public class BrokerConfigResolver {

    /** 这些字段的值如果被填成凭据 ID，会自动解密展开。 */
    private static final String[] CREDENTIAL_FIELDS = {
            "username", "password", "token", "apiKey", "secret", "webhookUrl", "accessKey", "secretKey"
    };

    private final DataSourceService sources;

    public BrokerConfigResolver(DataSourceService sources) {
        this.sources = sources;
    }

    /** 合并数据源配置与节点配置，并展开凭据字段。 */
    public Map<String, Object> resolve(Map<String, Object> config) {
        Map<String, Object> result = new LinkedHashMap<>();
        String datasourceId = firstNonBlank(config, "datasource", "connection");
        if (!datasourceId.isBlank()) {
            result.putAll(sources.connectionFields(datasourceId));
        }
        result.putAll(config);
        for (String field : CREDENTIAL_FIELDS) {
            Object raw = result.get(field);
            if (raw == null || String.valueOf(raw).isBlank()) {
                continue;
            }
            String value = String.valueOf(raw);
            Map<String, Object> credential = sources.credentialFields(value);
            if (credential.isEmpty()) {
                continue;
            }
            Object configured = credential.get(field);
            if (configured != null && !String.valueOf(configured).isBlank()) {
                result.put(field, String.valueOf(configured));
            }
        }
        return result;
    }

    /** Kafka Bootstrap Servers：兼容数据源里常见的几种字段名。 */
    public static String bootstrapServers(Map<String, Object> config) {
        return firstNonBlank(config, "bootstrapServers", "brokers", "bootstrap", "servers");
    }

    /** MQTT Broker 地址。 */
    public static String mqttServerUri(Map<String, Object> config) {
        String uri = firstNonBlank(config, "serverUri", "brokerUri", "url", "uri", "broker");
        return uri.isBlank() ? "" : (uri.startsWith("tcp://") || uri.startsWith("ssl://")
                || uri.startsWith("ws://") || uri.startsWith("wss://") ? uri : "tcp://" + uri);
    }

    public static int intValue(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean boolValue(Map<String, Object> config, String key, boolean fallback) {
        Object value = config.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        if ("是".equals(text) || "true".equalsIgnoreCase(text) || "1".equals(text)) return true;
        if ("否".equals(text) || "false".equalsIgnoreCase(text) || "0".equals(text)) return false;
        return fallback;
    }

    /** 生成订阅配置的稳定签名，用于判断触发器是否需要重建。 */
    public String signature(String flowId, String nodeId, Map<String, Object> resolved) {
        return flowId + "|" + nodeId + "|" + JsonUtil.stringify(resolved);
    }

    private static String firstNonBlank(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return "";
    }
}
