/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容 Chat Completions 客户端。
 *
 * <p>DeepSeek、智谱 GLM、通义千问、腾讯混元都提供 OpenAI 兼容的
 * {@code /chat/completions} 接口，差异只在地址、模型名与 Key，因此统一用一套
 * 请求/响应结构：请求 {@code {"model","messages"}}，取 {@code choices[0].message.content}。</p>
 *
 * <p>无状态、短连接：每次调用新建 HttpClient，配置变更即时生效，且避免缓存旧 Key。</p>
 */
@Component
public class LlmClient {

    /** 单次模型调用超时；设置页的连接测试也走这里，避免长时间挂起。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    /**
     * 发起一次对话，返回首个 choice 的文本内容。
     *
     * @param endpoint 完整 Chat Completions 地址
     * @param apiKey   API Key
     * @param model    模型名
     * @param prompt   用户输入
     * @return 模型回复文本
     * @throws IllegalStateException 调用失败或非 2xx
     */
    public String chat(String endpoint, String apiKey, String model, String prompt) {
        HttpResponse<String> response = send(endpoint, apiKey, model, List.of(prompt), TIMEOUT);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String detail = response.body() == null ? "" : response.body();
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "…";
            }
            throw new IllegalStateException("模型返回 HTTP " + response.statusCode()
                    + (detail.isEmpty() ? "" : "：" + detail));
        }
        JsonNode tree = JsonUtil.tree(response.body());
        if (tree == null) {
            throw new IllegalStateException("模型返回内容不是合法 JSON");
        }
        JsonNode content = tree.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode() || content.isNull()) {
            throw new IllegalStateException("模型响应缺少 choices[0].message.content");
        }
        return content.asText();
    }

    /**
     * 连接测试：用一个极短的问题验证地址、Key 与模型是否可用。
     *
     * <p>成功时返回 {@code {success:true, message, model, latencyMs}}；失败返回
     * {@code {success:false, message}}，不抛异常，方便前端直接展示。</p>
     */
    public Map<String, Object> test(String endpoint, String apiKey, String model) {
        long start = System.currentTimeMillis();
        try {
            String reply = chat(endpoint, apiKey, model, "只回复两个字：可用");
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

    /** 构造并发送请求。 */
    private HttpResponse<String> send(String endpoint, String apiKey, String model,
                                      List<String> prompts, Duration timeout) {
        try {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("接口地址不能为空");
            }
            List<Map<String, String>> messages = prompts.stream()
                    .map(p -> Map.of("role", "user", "content", p))
                    .toList();
            String body = JsonUtil.stringify(Map.of("model", model == null ? "" : model,
                    "messages", messages));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
            return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("模型调用失败：" + e.getMessage(), e);
        }
    }
}
