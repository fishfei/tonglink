/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.datasource.DataSourceService;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 告警通知节点：真实发送邮件 / Webhook / 企微 / 钉钉告警。
 *
 * <p>历史行为是只回一条 written 回执，渠道、模板、抑制字段全被忽略，导致「跑成功了但没人收到」。
 * 现在按以下规则工作：</p>
 * <ol>
 *   <li>配置了通知凭据（数据源），或目标地址是真实地址时，执行真实投递；</li>
 *   <li>都没配置时保持演示行为，返回带 {@code mode=simulated} 的回执，方便没有第三方账号时演示编排；</li>
 *   <li>投递失败抛业务异常，节点显示 FAILED，不再静默成功。</li>
 * </ol>
 *
 * <p>重复 suppresses：按「渠道 + 目标 + 级别 + 标题」记录上次真实发送时间，
 * 在 suppressMinutes 窗口内只回 suppressed，不重复打扰。</p>
 */
@Component
public class AlertExecutor implements NodeExecutor {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*\\}\\}");

    private final DataSourceService sources;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public AlertExecutor(DataSourceService sources) {
        this.sources = sources;
    }

    @Override
    public Set<String> supports() {
        return Set.of("output:alert");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        Map<String, Object> cfg = config == null ? Map.of() : config;
        String channel = str(cfg.get("channel"), "企业微信");
        String credentialId = str(cfg.get("credential"), "");
        String target = str(cfg.get("webhook"), "");
        String severity = str(cfg.get("severity"), "警告");
        Map<String, Object> cred = sources.connectionFields(credentialId);
        if (target.isBlank() && cred.containsKey("webhookUrl")) {
            target = str(cred.get("webhookUrl"), "");
        }
        Map<String, Object> first = input == null || input.isEmpty()
                ? Map.of() : input.get(0);

        String title = render(str(cfg.get("titleTemplate"), ""), first, context.vars());
        String message = render(str(cfg.get("messageTemplate"), ""), first, context.vars());
        if (title.isBlank()) {
            title = "【" + severity + "】" + context.flowId() + " 告警";
        }
        if (message.isBlank()) {
            message = "流程 " + context.flowId() + " 触发 " + severity + " 告警，时间 "
                    + LocalDateTime.now().withNano(0);
        }

        String finalTarget = target;
        boolean real = !cred.isEmpty() || looksReal(channel, finalTarget);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("channel", channel);
        row.put("severity", severity);
        row.put("target", finalTarget);
        row.put("subject", title);
        row.put("message", message);
        row.put("count", input == null ? 0 : input.size());
        row.put("ts", LocalDateTime.now().withNano(0).toString());

        if (!real) {
            row.put("status", "written");
            row.put("mode", "simulated");
            return new ExecResult(List.of(row), null);
        }

        long suppressMinutes = parseLong(cfg.get("suppressMinutes"), 0);
        String key = channel + "|" + finalTarget + "|" + severity + "|" + title;
        Instant previous = suppressMinutes > 0 ? lastSent.get(key) : null;
        if (previous != null && Duration.between(previous, Instant.now()).toMinutes() < suppressMinutes) {
            row.put("status", "suppressed");
            row.put("mode", "real");
            return new ExecResult(List.of(row), null);
        }

        if ("邮件".equals(channel)) {
            sendMail(cred, finalTarget, title, message, severity);
        } else {
            sendWebhook(cred, channel, finalTarget, title, message, severity, context.flowId(), first);
        }
        if (suppressMinutes > 0) {
            lastSent.put(key, Instant.now());
        }
        row.put("status", "sent");
        row.put("mode", "real");
        return new ExecResult(List.of(row), null);
    }

    /** 模板渲染：支持 {{ $json.a.b }}、{{ $json }}（整行 JSON）与 {{ $var.x }}。 */
    static String render(String template, Map<String, Object> row, Map<String, Object> vars) {
        if (template == null || template.isBlank()) return "";
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String expr = matcher.group(1).trim();
            Object value = resolve(expr, row, vars);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static Object resolve(String expr, Map<String, Object> row, Map<String, Object> vars) {
        if ("$json".equals(expr)) return JsonUtil.stringify(row);
        if (expr.startsWith("$json.")) return path(row, expr.substring(6));
        if (expr.startsWith("$var.")) return path(vars, expr.substring(5));
        return path(row, expr);
    }

    private static Object path(Map<String, Object> source, String path) {
        if (source == null || path == null || path.isBlank()) return null;
        Object current = source;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    /** 没有凭据时，只有看起来是真实地址才投递；示例里的占位地址继续走模拟模式。 */
    private static boolean looksReal(String channel, String target) {
        if (target.isBlank() || target.contains("请填写") || target.contains("example.local")) return false;
        if ("邮件".equals(channel)) return target.contains("@");
        return target.startsWith("http://") || target.startsWith("https://");
    }

    private static void sendMail(Map<String, Object> cred, String to, String title,
                                 String message, String severity) {
        String host = str(cred.get("smtpHost"), "");
        String port = str(cred.get("smtpPort"), "465");
        String encryption = str(cred.get("encryption"), "SSL");
        String username = str(cred.get("username"), "");
        String password = str(cred.get("password"), "");
        String from = str(cred.get("from"), username);
        if (host.isBlank()) {
            throw new ApiException("邮件告警缺少 SMTP 服务器，请选择邮件 SMTP 数据源作为通知凭据");
        }
        if (to.isBlank() || !to.contains("@")) {
            throw new ApiException("邮件告警缺少收件人，请在「Webhook 地址 / 收件人」填写邮箱地址");
        }
        if (from.isBlank()) {
            throw new ApiException("邮件告警缺少发件账号，请在通知凭据中填写发件账号");
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", port);
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.auth", Boolean.toString(!username.isBlank()));
        if ("SSL".equalsIgnoreCase(encryption)) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", port);
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if ("STARTTLS".equalsIgnoreCase(encryption)) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        Session session = Session.getInstance(props, username.isBlank() ? null : new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
        try {
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(from));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            mime.setSubject("[" + severity + "] " + title, "UTF-8");
            mime.setText(message + System.lineSeparator() + System.lineSeparator()
                    + "严重级别：" + severity + System.lineSeparator()
                    + "本邮件由 TongLink Studio 告警通知节点自动发送。", "UTF-8");
            mime.setSentDate(new java.util.Date());
            Transport.send(mime);
        } catch (Exception e) {
            throw new ApiException("邮件告警发送失败：" + e.getMessage());
        }
    }

    private void sendWebhook(Map<String, Object> cred, String channel, String url,
                             String title, String message, String severity,
                             String flowId, Map<String, Object> row) {
        if (url.isBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new ApiException(channel + "告警缺少有效的 Webhook 地址：" + url);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if ("Webhook".equals(channel)) {
            payload.put("title", title);
            payload.put("text", message);
            payload.put("severity", severity);
            payload.put("flowId", flowId);
            payload.put("data", row);
        } else {
            payload.put("msgtype", "text");
            Map<String, Object> text = new LinkedHashMap<>();
            text.put("content", "[" + severity + "] " + title + System.lineSeparator() + message);
            payload.put("text", text);
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.stringify(payload),
                        java.nio.charset.StandardCharsets.UTF_8));
        String apiKey = str(cred.get("apiKey"), "");
        if (!apiKey.isBlank()) builder.header("Authorization", "Bearer " + apiKey);
        try {
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(channel + "告警 Webhook 返回 HTTP " + response.statusCode()
                        + "：" + response.body().substring(0, Math.min(200, response.body().length())));
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(channel + "告警 Webhook 请求失败：" + e.getMessage());
        }
    }

    private static long parseLong(Object value, long fallback) {
        if (value == null || String.valueOf(value).isBlank()) return fallback;
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
