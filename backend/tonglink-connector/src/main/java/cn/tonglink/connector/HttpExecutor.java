/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.DataSourceService;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HTTP 节点执行器：同时处理 HTTP 数据源读取与 HTTP 推送输出。
 *
 * <p>演示环境的示例地址（{@code *.factory.local}）在本地不可达，此时返回模拟结果，
 * 避免刚部署时每次运行都失败；真实地址走真实请求，失败如实抛异常。</p>
 */
@Component
public class HttpExecutor implements NodeExecutor {

    /** 示例域名，命中即视为演示配置。 */
    private static final String DEMO_HOST = "factory.local";

    private final HttpClient client;
    private final DataSourceService sources;

    public HttpExecutor(DataSourceService sources,
                        @Value("${tonglink.http.proxy:}") String configuredProxy) {
        this.sources = sources;
        this.client = buildClient(configuredProxy);
    }

    /**
     * Java HttpClient 不读取 HTTP_PROXY/HTTPS_PROXY 环境变量，而很多内网环境只有代理能出外网。
     * 这里显式识别环境变量，避免「命令行能访问、流程里连接超时」的假失败。
     */
    private static HttpClient buildClient(String configuredProxy) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8));
        String proxy = firstNonBlank(configuredProxy, System.getenv("HTTPS_PROXY"), System.getenv("https_proxy"),
                System.getenv("HTTP_PROXY"), System.getenv("http_proxy"));
        if (proxy != null && !proxy.isBlank()) {
            try {
                URI uri = URI.create(proxy);
                builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort())));
            } catch (Exception ignored) {
                // 代理配置非法时退回直连
            }
        }
        return builder.build();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    @Override
    public Set<String> supports() {
        return Set.of("source:http", "output:http");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String url = String.valueOf(config.getOrDefault("url", ""));
        if (url.isBlank() || url.contains(DEMO_HOST)) {
            Map<String, Object> simulated = new LinkedHashMap<>();
            simulated.put("status", "simulated");
            simulated.put("url", url);
            simulated.put("count", input.size());
            return new ExecResult(List.of(simulated), null);
        }
        try {
            String method = String.valueOf(config.getOrDefault("method", "GET")).toUpperCase();
            String body = render(String.valueOf(config.getOrDefault("bodyTemplate", "")), input);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(12))
                    .header("Content-Type", "application/json");

            // 凭据现在属于数据源：解密后以 Bearer 形式注入，日志与输出里都不会出现明文
            String credentialId = String.valueOf(config.getOrDefault("auth", ""));
            if (!credentialId.isBlank() && !"null".equals(credentialId)) {
                String token = bearerToken(sources.credentialFields(credentialId));
                if (!token.isBlank()) {
                    builder.header("Authorization", "Bearer " + token);
                }
            }

            builder.method(method, HttpRequest.BodyPublishers.ofString(body.isBlank() ? "{}" : body));
            // 外网接口偶发抖动，重试 3 次再判失败
            HttpResponse<String> response = null;
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                    break;
                } catch (Exception e) {
                    lastError = e;
                    if (attempt < 3) Thread.sleep(400L * attempt);
                }
            }
            if (response == null) {
                String fallback = String.valueOf(config.getOrDefault("fallbackBody", ""));
                if (!fallback.isBlank() && !"null".equals(fallback)) {
                    // 内置测试流允许配置兜底响应：明确标记 simulated，不把降级伪装成真实数据
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("status", 200);
                    row.put("simulated", true);
                    row.put("reason", "外部接口不可达，使用内置示例数据：" + (lastError == null ? "" : lastError.getMessage()));
                    row.put("body", fallback);
                    return new ExecResult(List.of(row), null);
                }
                throw lastError == null ? new IllegalStateException("HTTP 请求无响应") : lastError;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", response.statusCode());
            row.put("body", response.body());
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            throw new IllegalStateException("HTTP 节点执行失败：" + e.getMessage(), e);
        }
    }

    /**
     * 渲染请求体模板。
     *
     * <p>支持 {@code {{ $json }}}（首行数据）与 {@code {{ $input }}}（全部输入）两种占位符，
     * 覆盖大多数推送场景，无需引入完整模板引擎。</p>
     */
    private String render(String template, List<Map<String, Object>> input) {
        if (template.isBlank()) {
            return "{}";
        }
        String first = input.isEmpty() ? "{}" : JsonUtil.stringify(input.get(0));
        return template
                .replace("{{ $json }}", first)
                .replace("{{$json}}", first)
                .replace("{{ $input }}", JsonUtil.stringify(input));
    }

    /** 从数据源凭据字段中取 Bearer Token。 */
    private String bearerToken(Map<String, Object> fields) {
        for (String key : List.of("apiKey", "token", "accessToken", "value")) {
            Object value = fields.get(key);
            if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);
        }
        return "";
    }
}
