/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 Chat Completions 客户端。
 *
 * <p>DeepSeek、智谱 GLM、通义千问、腾讯混元都提供 OpenAI 兼容的
 * {@code /chat/completions} 接口，差异只在地址、模型名与 Key，因此统一用一套
 * 请求/响应结构：请求 {@code {"model","messages"}}，取 {@code choices[0].message.content}。</p>
 *
 * <p>支持普通请求与 SSE 流式请求。流式用于 AI 助手问答，让长回答逐段可见，
 * 避免用户面对空白页面等到超时；非流式仍用于流程生成与诊断（需要完整 JSON）。</p>
 *
 * <p>无状态、短连接：每次调用新建 HttpClient，配置变更即时生效，且避免缓存旧 Key。</p>
 */
@Component
public class LlmClient {

    /** 连接测试用短超时，避免设置页点一次测试卡很久。 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    /** 正式模型调用超时；国内大模型生成长文本时 30 秒明显不够。 */
    private static final Duration CHAT_TIMEOUT = Duration.ofSeconds(120);

    /** 发起一次对话，返回首个 choice 的文本内容（保持旧调用方式可用）。 */
    public String chat(String endpoint, String apiKey, String model, String prompt) {
        return chat(endpoint, apiKey, model, null, prompt);
    }

    /** 发起一次带系统提示词的对话，返回首个 choice 的文本内容。 */
    public String chat(String endpoint, String apiKey, String model, String system, String prompt) {
        return chat(endpoint, apiKey, model, system, prompt, CHAT_TIMEOUT);
    }

    private String chat(String endpoint, String apiKey, String model, String system,
                        String prompt, Duration timeout) {
        HttpRequest request = request(endpoint, apiKey, model, messages(system, prompt), false, timeout);
        HttpResponse<String> response = send(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("模型返回 HTTP " + response.statusCode()
                    + detail(response.body()));
        }
        JsonNode tree = JsonUtil.tree(response.body());
        if (tree == null) {
            throw new IllegalStateException("模型返回内容不是合法 JSON");
        }
        JsonNode error = tree.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException("模型返回错误：" + error.path("message").asText(error.toString()));
        }
        JsonNode content = tree.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("模型响应缺少 choices[0].message.content");
        }
        return content.asText();
    }

    /**
     * SSE 流式对话：每解析出一个增量片段就回调 {@code onDelta}。
     *
     * <p>只读取 {@code choices[0].delta.content}；遇到 {@code [DONE]} 或流结束即返回。
     * 若上游返回 error 对象或非 2xx，抛异常由调用方决定如何回退。</p>
     */
    public void stream(String endpoint, String apiKey, String model, String system,
                       String prompt, Consumer<String> onDelta) {
        HttpRequest request = request(endpoint, apiKey, model, messages(system, prompt), true, CHAT_TIMEOUT);
        HttpResponse<InputStream> response = sendStream(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("模型返回 HTTP " + response.statusCode()
                    + detail(readHead(response.body())));
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JsonNode tree = JsonUtil.tree(data);
                if (tree == null) {
                    continue;
                }
                JsonNode error = tree.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new IllegalStateException("模型返回错误："
                            + error.path("message").asText(error.toString()));
                }
                JsonNode delta = tree.path("choices").path(0).path("delta").path("content");
                if (delta.isMissingNode() || delta.isNull()) {
                    // 少数兼容实现在 stream 模式下仍返回 message.content
                    delta = tree.path("choices").path(0).path("message").path("content");
                }
                if (!delta.isMissingNode() && !delta.isNull() && !delta.asText().isEmpty()) {
                    onDelta.accept(delta.asText());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取模型流式响应失败：" + e.getMessage(), e);
        }
    }

    /** 连接测试：用一个极短的问题验证地址、Key 与模型是否可用。 */
    public Map<String, Object> test(String endpoint, String apiKey, String model) {
        long start = System.currentTimeMillis();
        try {
            String reply = chat(endpoint, apiKey, model, null, "只回复两个字：可用", TEST_TIMEOUT);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", reply == null || reply.isBlank() ? "连接成功（返回为空）" : "连接成功：" + reply);
            result.put("model", model);
            result.put("latencyMs", System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", false);
            result.put("message", e.getMessage() == null ? e.toString() : e.getMessage());
            result.put("latencyMs", System.currentTimeMillis() - start);
            return result;
        }
    }

    private List<Map<String, String>> messages(String system, String prompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", prompt == null ? "" : prompt));
        return messages;
    }

    private HttpRequest request(String endpoint, String apiKey, String model,
                                List<Map<String, String>> messages, boolean stream, Duration timeout) {
        try {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("接口地址不能为空");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model == null ? "" : model);
            body.put("messages", messages);
            if (stream) {
                body.put("stream", true);
            }
            return HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", stream ? "text/event-stream" : "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.stringify(body)))
                    .build();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("构造模型请求失败：" + e.getMessage(), e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
        }
    }

    private HttpResponse<InputStream> sendStream(HttpRequest request) {
        try {
            return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
        }
    }

    private String detail(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        return body.length() > 300 ? "：" + body.substring(0, 300) + "…" : "：" + body;
    }

    private String readHead(InputStream in) {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && sb.length() < 300) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }
}
