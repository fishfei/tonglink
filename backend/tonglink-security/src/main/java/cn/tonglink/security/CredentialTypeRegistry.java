/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 连接与认证类型目录。
 *
 * <p>参考 n8n 的做法：类型自身声明一组字段，前端按类型渲染唯一一张表单，
 * 后端按同一份定义做字段校验、协议推导与密码掩码。连接地址和认证信息都在同一组字段里，
 * 不再拆成「连接参数 + 凭据」两个重复区块。</p>
 */
@Component
public class CredentialTypeRegistry {

    /** 全部类型，顺序即前端下拉顺序。 */
    private final Map<String, Map<String, Object>> types = new LinkedHashMap<>();

    public CredentialTypeRegistry() {
        add("opcua", "OPC UA 连接", "OT", "OPC UA",
                field("endpoint", "服务端地址", "text", true, "opc.tcp://127.0.0.1:4840"),
                field("namespace", "命名空间", "text", false, "3"),
                field("username", "用户名", "text", false, "可选"),
                field("password", "密码", "password", false, "可选"));
        add("modbus", "Modbus TCP 连接", "OT", "Modbus TCP",
                field("host", "主机", "text", true, "127.0.0.1"),
                field("port", "端口", "text", false, "502"),
                field("unitId", "从站号", "text", false, "1"));
        add("mqtt", "MQTT 连接", "OT", "MQTT",
                field("uri", "Broker 地址", "text", true, "tcp://127.0.0.1:1883"),
                field("username", "用户名", "text", false, "可选"),
                field("password", "密码", "password", false, "可选"));
        add("kafka", "Kafka 连接", "IT", "Kafka",
                field("bootstrapServers", "Bootstrap Servers", "text", true, "127.0.0.1:9092"),
                field("username", "SASL 用户名", "text", false, "可选"),
                field("password", "SASL 密码", "password", false, "可选"),
                field("saslMechanism", "SASL 机制", "text", false, "PLAIN"));
        add("database", "数据库连接", "IT", "MySQL",
                select("driver", "数据库类型", true,
                        "MySQL", "PostgreSQL", "Oracle", "SQL Server"),
                field("host", "主机", "text", false, "127.0.0.1（或填写完整 JDBC URL）"),
                field("port", "端口", "text", false, "3306"),
                field("database", "数据库名（可选）", "text", false, "留空则连接实例默认库，随后由 SQL 指定库"),
                field("jdbcUrl", "完整 JDBC URL（可选）", "text", false,
                        "jdbc:mysql://127.0.0.1:3306/tonglink；填写后优先于上面的主机/端口/库名"),
                field("username", "用户名", "text", true, "root"),
                field("password", "密码", "password", true, "数据库密码"));
        add("http", "HTTP 接口", "IT", "HTTP/REST",
                field("baseUrl", "接口基础地址", "text", true, "https://api.example.com"),
                field("apiKey", "API Key（可选）", "password", false, "粘贴令牌"));
        add("feishu", "飞书应用", "IT", "HTTP/REST",
                field("appId", "App ID", "text", true, "cli_xxx"),
                field("appSecret", "App Secret", "password", true, "飞书开放平台密钥"),
                field("tenantKey", "租户 Key", "text", false, "可选"));
        add("kingdee", "金蝶云星空", "IT", "HTTP/REST",
                field("serverUrl", "服务地址", "text", true, "http://kingdee.local/k3cloud"),
                field("account", "账套", "text", true, "账套 ID"),
                field("dbName", "数据库", "text", true, "数据库名"),
                field("username", "用户名", "text", true, "管理员账号"),
                field("password", "密码", "password", true, "留空表示不修改"));
        add("email", "邮件 SMTP", "IT", "SMTP",
                field("smtpHost", "SMTP 服务器", "text", true, "smtp.126.com"),
                field("smtpPort", "端口", "text", false, "465（SSL）/ 587（STARTTLS）/ 25"),
                select("encryption", "加密方式", false, "SSL", "STARTTLS", "无"),
                field("username", "发件账号", "text", true, "完整邮箱地址"),
                field("password", "授权码 / 密码", "password", true, "邮箱 SMTP 授权码"),
                field("from", "发件人显示地址", "text", false, "留空则使用发件账号"));
        add("webhook", "Webhook", "IT", "HTTP/REST",
                field("webhookUrl", "Webhook 地址", "text", true, "https://..."),
                field("secret", "签名密钥", "password", false, "可选"));
        add("api_key", "API Key / Bearer Token", "IT", "HTTP/REST",
                field("apiKey", "API Key", "password", true, "粘贴令牌"),
                field("baseUrl", "接口基础地址", "text", false, "https://api.example.com"));
        add("basic", "用户名密码", "IT", "HTTP/REST",
                field("username", "用户名", "text", true, "用户名"),
                field("password", "密码", "password", true, "密码"));
    }

    /** 类型对应的类别，由类型唯一决定，前端不再手填。 */
    public String category(String key) {
        return str(type(key).get("category"), "IT");
    }

    /**
     * 类型对应的协议，用于节点配置的数据源下拉过滤。
     * 数据库类型按 driver 细分，其余类型固定。
     */
    public String protocol(String key, Map<String, Object> fields) {
        Map<String, Object> t = type(key);
        String base = str(t.get("protocol"), "HTTP/REST");
        if ("database".equals(key) && fields != null) {
            return switch (str(fields.get("driver"), "MySQL")) {
                case "PostgreSQL" -> "PostgreSQL";
                case "Oracle" -> "Oracle";
                case "SQL Server" -> "SQL Server";
                default -> "MySQL";
            };
        }
        return base;
    }

    public List<Map<String, Object>> list() {
        return new ArrayList<>(types.values());
    }

    public Map<String, Object> type(String key) {
        return types.getOrDefault(key, Map.of());
    }

    /** 凭据值的 JSON 中哪些字段属于密码，需要掩码。 */
    @SuppressWarnings("unchecked")
    public List<String> secretFields(String typeKey) {
        Map<String, Object> type = type(typeKey);
        Object raw = type.get("fields");
        if (!(raw instanceof List<?> fields)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object f : fields) {
            Map<String, Object> field = JsonMap.of(f);
            if ("password".equals(field.get("type"))) {
                result.add(String.valueOf(field.get("key")));
            }
        }
        return result;
    }

    private void add(String key, String label, String category, String protocol,
                     Map<String, Object>... fields) {
        Map<String, Object> type = new LinkedHashMap<>();
        type.put("key", key);
        type.put("label", label);
        type.put("category", category);
        type.put("protocol", protocol);
        type.put("fields", List.of(fields));
        types.put(key, type);
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static Map<String, Object> field(String key, String label, String type,
                                             boolean required, String placeholder) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("key", key);
        f.put("label", label);
        f.put("type", type);
        f.put("required", required);
        f.put("placeholder", placeholder);
        return f;
    }

    private static Map<String, Object> select(String key, String label, boolean required,
                                              String... options) {
        Map<String, Object> f = field(key, label, "select", required, "");
        f.put("options", List.of(options));
        return f;
    }

    /** 内部小工具：把 Object 安全转成 Map。 */
    private static final class JsonMap {
        @SuppressWarnings("unchecked")
        static Map<String, Object> of(Object value) {
            return value instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
        }
    }
}
