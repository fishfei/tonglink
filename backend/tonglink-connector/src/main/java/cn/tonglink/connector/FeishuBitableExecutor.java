/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.Credential;
import cn.tonglink.repository.MetaRepository;
import cn.tonglink.security.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 飞书多维表格写入执行器。
 *
 * <p>生产路径：用凭据里的 App ID / App Secret 换取 tenant_access_token，再调用
 * {@code bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_create} 批量新增记录。
 * 凭据支持两种格式：JSON（{@code {"appId":"cli_xxx","appSecret":"xxx"}}）或单独的 Secret
 * （此时 App ID 取凭据名称或配置里的 appId）。</p>
 *
 * <p>生产语义：缺少 appToken/tableId、未配置凭据、凭据含占位符或飞书接口报错时
 * 一律抛出 {@link IllegalStateException}，让节点与整次执行标记为 FAILED。
 * 不存在"模拟成功"路径，避免未配置令牌却显示执行成功。</p>
 */
@Component
public class FeishuBitableExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(FeishuBitableExecutor.class);
    private static final String TOKEN_URL =
            "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
    private static final String RECORDS_URL =
            "https://open.feishu.cn/open-apis/bitable/v1/apps/%s/tables/%s/records/batch_create";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final MetaRepository meta;
    private final CryptoService crypto;

    public FeishuBitableExecutor(MetaRepository meta, CryptoService crypto) {
        this.meta = meta;
        this.crypto = crypto;
    }

    @Override
    public Set<String> supports() {
        return Set.of("output:feishu_bitable");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String appToken = str(config.get("appToken"));
        String tableId = str(config.get("tableId"));
        String credentialId = str(config.get("credential"));
        if (appToken.isBlank() || tableId.isBlank() || appToken.startsWith("请填写")) {
            throw new IllegalStateException("飞书节点缺少 appToken 或 tableId，请在节点配置中填写多维表格地址信息");
        }

        Credential credential = credentialId.isBlank() ? null : meta.credential(credentialId);
        if (credential == null) {
            throw new IllegalStateException("飞书节点未选择有效凭据：请在「变量与凭据」中配置 App ID / App Secret 并在节点中选择");
        }
        String secret;
        try {
            secret = crypto.decrypt(credential.valueCipher());
        } catch (Exception e) {
            throw new IllegalStateException("飞书凭据解密失败：" + e.getMessage(), e);
        }
        if (secret.isBlank() || secret.startsWith("demo-") || secret.contains("EXAMPLE")
                || secret.contains("your-") || secret.contains("请填写")) {
            throw new IllegalStateException("飞书凭据是占位符（未配置真实 App Secret），请在「变量与凭据」中更新");
        }

        try {
            App app = parseApp(credential, config, secret);
            if (app.appId().isBlank()) {
                throw new IllegalStateException(
                        "飞书凭据缺少 App ID（appId），请配置为 {\"appId\":\"cli_xxx\",\"appSecret\":\"xxx\"}");
            }
            String token = tenantAccessToken(app.appId(), app.appSecret());
            List<Map<String, Object>> fields = fields(input, config);
            int batchSize = parseInt(config.get("batchSize"), 100);
            int written = 0;
            for (int from = 0; from < fields.size(); from += batchSize) {
                List<Map<String, Object>> batch = fields.subList(from, Math.min(from + batchSize, fields.size()));
                createRecords(token, appToken, tableId, batch);
                written += batch.size();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", "written");
            row.put("simulated", false);
            row.put("appToken", appToken);
            row.put("tableId", tableId);
            row.put("count", written);
            row.put("ts", LocalDateTime.now().withNano(0).toString());
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            log.warn("飞书多维表格写入失败：{}", e.getMessage());
            throw new IllegalStateException("飞书多维表格写入失败：" + e.getMessage(), e);
        }
    }

    /** 解析 App ID / App Secret，兼容 JSON 凭据与单独 Secret。 */
    private App parseApp(Credential credential, Map<String, Object> config, String secret) {
        try {
            Map<String, Object> parsed = JsonUtil.parse(secret, new TypeReference<Map<String, Object>>() {
            });
            if (parsed != null && parsed.get("appSecret") != null) {
                return new App(str(parsed.get("appId")), str(parsed.get("appSecret")));
            }
        } catch (Exception ignored) {
            // 非 JSON 格式，按单独的 Secret 处理
        }
        String appId = str(config.get("appId"));
        if (appId.isBlank() && credential.name() != null && credential.name().startsWith("cli_")) {
            appId = credential.name();
        }
        return new App(appId, secret);
    }

    /** 用 appId/appSecret 换取 tenant_access_token。 */
    private String tenantAccessToken(String appId, String appSecret) throws Exception {
        if (appId.isBlank()) {
            throw new IllegalStateException("凭据缺少 App ID（appId），请使用 {\"appId\":\"cli_xxx\",\"appSecret\":\"xxx\"} 格式");
        }
        Map<String, String> authBody = new LinkedHashMap<>();
        authBody.put("app_id", appId);
        authBody.put("app_secret", appSecret);
        String body = JsonUtil.stringify(authBody);
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = JsonUtil.parse(response.body(), new TypeReference<Map<String, Object>>() {
        });
        if (parsed == null || parsed.get("code") == null || Integer.parseInt(String.valueOf(parsed.get("code"))) != 0) {
            throw new IllegalStateException("获取 tenant_access_token 失败：" + abbreviate(response.body()));
        }
        String value = str(parsed.get("tenant_access_token"));
        if (value.isBlank()) {
            throw new IllegalStateException("飞书未返回 tenant_access_token");
        }
        return value;
    }

    /** 批量写入记录。 */
    private void createRecords(String token, String appToken, String tableId,
                               List<Map<String, Object>> batch) throws Exception {
        List<Map<String, Object>> records = new ArrayList<>();
        for (Map<String, Object> row : batch) {
            records.add(Map.of("fields", row));
        }
        String body = JsonUtil.stringify(Map.of("records", records));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(String.format(RECORDS_URL, appToken, tableId)))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> parsed = JsonUtil.parse(response.body(), new TypeReference<Map<String, Object>>() {
        });
        if (parsed == null || parsed.get("code") == null || Integer.parseInt(String.valueOf(parsed.get("code"))) != 0) {
            throw new IllegalStateException("写入记录失败：" + abbreviate(response.body()));
        }
    }

    /**
     * 按 fieldMapping 把输入行映射成飞书字段。
     * mapping 形如 {"日期":"date","最高温":"tempMax"}（飞书字段名 -> 输入字段名）；
     * 未配置时直接透传输入行。
     */
    private List<Map<String, Object>> fields(List<Map<String, Object>> input, Map<String, Object> config) {
        String rawMapping = str(config.get("fieldMapping"));
        Map<String, Object> mapping = rawMapping.isBlank() ? Map.of()
                : JsonUtil.parse(rawMapping, new TypeReference<Map<String, Object>>() {
                });
        if (mapping == null) {
            mapping = Map.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : input) {
            Map<String, Object> fields = new LinkedHashMap<>();
            if (mapping.isEmpty()) {
                fields.putAll(row);
            } else {
                mapping.forEach((feishuField, sourceField) ->
                        fields.put(feishuField, row.get(String.valueOf(sourceField))));
            }
            result.add(fields);
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static int parseInt(Object value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).replace("ms", "").trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String abbreviate(String text) {
        return text == null ? "" : text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private record App(String appId, String appSecret) {
    }
}
