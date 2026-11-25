/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.DataSourceService;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.node.NodeRegistry;
import cn.tonglink.taskrunner.CodeRunnerService;
import com.fasterxml.jackson.core.type.TypeReference;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.sync.RedisCommands;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 节点包的真实执行器。
 *
 * <p>节点管理里安装的包不是「多画一个表单」：安装时 NodeRegistry 会校验并要求声明
 * {@code runtime.kind}，这里按 kind 调用真实后端逻辑（HTTP / 脚本 / Redis / 企微 / InfluxDB）。
 * 插件执行器是动态的，新包安装后无需重启即可在流程中使用。</p>
 */
@Component
public class PluginExecutor implements DynamicNodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(PluginExecutor.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_.]+)\\}\\}");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> SUPPORTED = Set.of("http", "script", "redis", "wecom", "influxdb");

    private final NodeRegistry registry;
    private final CodeRunnerService runner;
    private final DataSourceService sources;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public PluginExecutor(NodeRegistry registry, CodeRunnerService runner,
                          DataSourceService sources) {
        this.registry = registry;
        this.runner = runner;
        this.sources = sources;
    }

    @Override
    public Set<String> supports() {
        return Set.of();
    }

    @Override
    public boolean supports(GraphNode node) {
        if (node == null) return false;
        Map<String, Object> runtime = registry.runtimeFor(node.type(), node.subtype());
        return SUPPORTED.contains(str(runtime.get("kind")));
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        Map<String, Object> runtime = registry.runtimeFor(context.node().type(), context.node().subtype());
        String kind = str(runtime.get("kind"));
        return switch (kind) {
            case "http" -> http(runtime, config, input);
            case "script" -> script(runtime, config, input, context.vars());
            case "redis" -> redis(config, input);
            case "wecom" -> wecom(config, input);
            case "influxdb" -> influxdb(config, input);
            default -> throw new IllegalStateException("节点包没有可用的运行时实现：" + kind);
        };
    }

    /** 通用 HTTP 插件：runtime 定义 method/url，节点配置可覆盖并用 {{key}} 插值。 */
    private ExecResult http(Map<String, Object> runtime, Map<String, Object> config,
                            List<Map<String, Object>> input) {
        Map<String, Object> merged = new LinkedHashMap<>(runtime);
        merged.putAll(config);
        String url = render(str(merged.get("url")), merged, input);
        String method = str(merged.getOrDefault("method", "POST")).toUpperCase();
        if (url.isBlank()) {
            throw new IllegalArgumentException("HTTP 插件节点缺少 url");
        }
        String body = render(str(merged.getOrDefault("body", "")), merged, input);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000,
                        longOf(merged.get("timeoutMs"), 10000))));
        Map<String, Object> headers = merged.get("headers") instanceof Map<?, ?>
                ? JsonUtil.toMap(merged.get("headers")) : Map.of();
        headers.forEach((k, v) -> builder.header(k, String.valueOf(v)));
        builder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", response.statusCode());
            row.put("body", response.body());
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 插件节点调用失败：" + e.getMessage(), e);
        }
    }

    /** 脚本插件：复用代码节点的子进程运行时，支持 Node.js / Python。 */
    private ExecResult script(Map<String, Object> runtime, Map<String, Object> config,
                              List<Map<String, Object>> input, Map<String, Object> vars) {
        String language = str(config.getOrDefault("language", runtime.getOrDefault("language", "nodejs")));
        String code = str(config.getOrDefault("code", runtime.get("code")));
        int timeout = (int) Math.min(longOf(runtime.get("timeoutSeconds"), 30), 300);
        CodeRunnerService.RunResult result = runner.run(language, code, input, vars, timeout);
        if (!result.ok()) {
            throw new IllegalStateException("插件脚本执行失败：" + result.error());
        }
        return new ExecResult(result.output(), null, result.logs());
    }

    /** Redis 插件：List / Stream / String 三种写入结构。 */
    private ExecResult redis(Map<String, Object> config, List<Map<String, Object>> input) {
        String uri = credentialValue(str(config.get("credential")), "uri");
        if (uri.isBlank()) {
            uri = "redis://127.0.0.1:6379";
        }
        String keyPattern = str(config.get("keyPattern"));
        if (keyPattern.isBlank()) {
            throw new IllegalArgumentException("Redis 插件节点缺少 keyPattern");
        }
        String dataType = str(config.getOrDefault("dataType", "List"));
        long ttl = longOf(config.get("ttl"), 0L);
        List<Map<String, Object>> results = new ArrayList<>();
        RedisClient client = RedisClient.create(uri);
        try (var connection = client.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            Map<String, Object> first = input.isEmpty() ? Map.of() : input.get(0);
            String key = render(keyPattern, config, input);
            for (Map<String, Object> row : input) {
                String payload = JsonUtil.stringify(row);
                switch (dataType) {
                    case "Stream" -> commands.xadd(key, Map.of("payload", payload));
                    case "String" -> commands.set(key, payload);
                    default -> commands.rpush(key, payload);
                }
                Map<String, Object> rowResult = new LinkedHashMap<>();
                rowResult.put("key", key);
                rowResult.put("dataType", dataType);
                rowResult.put("payload", payload);
                results.add(rowResult);
            }
            if (ttl > 0 && !results.isEmpty()) {
                commands.expire(key, ttl);
            }
            if (results.isEmpty()) {
                Map<String, Object> empty = new LinkedHashMap<>();
                empty.put("key", key);
                empty.put("affected", 0);
                empty.put("sample", first);
                results.add(empty);
            }
        } finally {
            client.shutdown();
        }
        return new ExecResult(results, null);
    }

    /** 企业微信机器人插件：Webhook 从凭据中取，内容支持 {{字段}} 插值。 */
    private ExecResult wecom(Map<String, Object> config, List<Map<String, Object>> input) {
        String webhook = credentialValue(str(config.get("credential")), "webhookUrl");
        if (webhook.isBlank()) {
            throw new IllegalArgumentException("企业微信插件节点缺少机器人 Webhook 凭据");
        }
        String msgType = str(config.getOrDefault("msgType", "markdown"));
        String content = render(str(config.getOrDefault("content", "")), config, input);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("msgtype", msgType);
        body.put(msgType, "markdown".equals(msgType)
                ? Map.of("content", content) : Map.of("content", content));
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.stringify(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", response.statusCode());
            row.put("body", response.body());
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            throw new IllegalStateException("企业微信机器人推送失败：" + e.getMessage(), e);
        }
    }

    /** InfluxDB 插件：按行协议批量写入，连接信息来自数据源。 */
    private ExecResult influxdb(Map<String, Object> config, List<Map<String, Object>> input) {
        String datasourceId = str(config.get("datasource"));
        DataSource ds = datasourceId.isBlank() ? null : sources.get(datasourceId);
        Map<String, Object> dsConfig = ds == null || ds.configJson() == null
                ? Map.of() : JsonUtil.parse(ds.configJson(), MAP_TYPE);
        String url = str(dsConfig.getOrDefault("url", "http://127.0.0.1:8086/api/v2/write"));
        Map<String, Object> cred = datasourceId.isBlank() ? Map.of()
                : sources.credentialFields(datasourceId);
        String token = str(cred.getOrDefault("token", dsConfig.get("token")));
        String org = str(dsConfig.get("org"));
        String bucket = str(dsConfig.get("bucket"));
        String measurement = str(config.get("measurement"));
        if (measurement.isBlank()) {
            throw new IllegalArgumentException("InfluxDB 插件节点缺少 measurement");
        }
        List<String> tagFields = str(config.get("tags")).isBlank()
                ? List.of() : List.of(str(config.get("tags")).split(","));
        StringBuilder lineProtocol = new StringBuilder();
        for (Map<String, Object> row : input) {
            lineProtocol.append(measurement);
            for (String tag : tagFields) {
                Object value = row.get(tag.trim());
                if (value != null) {
                    lineProtocol.append(',').append(tag.trim()).append('=').append(value);
                }
            }
            lineProtocol.append(' ');
            boolean firstField = true;
            for (Map.Entry<String, Object> e : row.entrySet()) {
                if (tagFields.contains(e.getKey())) continue;
                if (!firstField) lineProtocol.append(',');
                Object value = e.getValue();
                lineProtocol.append(e.getKey()).append('=')
                        .append(value instanceof Number ? value : "\"" + value + "\"");
                firstField = false;
            }
            lineProtocol.append('\n');
        }
        String endpoint = url + (url.contains("?") ? "&" : "?")
                + "org=" + encode(org) + "&bucket=" + encode(bucket) + "&precision=ms";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "text/plain; charset=utf-8")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(lineProtocol.toString()));
            if (!token.isBlank()) builder.header("Authorization", "Token " + token);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("InfluxDB 返回 " + response.statusCode() + "：" + response.body());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("written", input.size());
            row.put("measurement", measurement);
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            throw new IllegalStateException("InfluxDB 写入失败：" + e.getMessage(), e);
        }
    }

    /** 从数据源凭据中按字段名取值；找不到时用传入的 ID 本身，兼容直接写值的老配置。 */
    private String credentialValue(String credentialId, String field) {
        if (credentialId.isBlank()) return "";
        Map<String, Object> parsed = sources.credentialFields(credentialId);
        if (parsed.isEmpty()) return credentialId;
        Object value = parsed.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    /** 用节点配置和首条输入数据渲染 {{key}} / {{$json.xxx}} 占位符。 */
    private static String render(String template, Map<String, Object> config,
                                 List<Map<String, Object>> input) {
        if (template == null || template.isBlank()) return "";
        Map<String, Object> first = input == null || input.isEmpty() ? Map.of() : input.get(0);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String token = matcher.group(1);
            Object value;
            if (token.startsWith("$json.")) {
                value = first.get(token.substring(6));
            } else {
                value = config.get(token);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long longOf(Object value, long fallback) {
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
