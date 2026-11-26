/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.tonglink.common.ApiException;
import cn.tonglink.datasource.DataSourceService;
import cn.tonglink.model.flow.GraphNode;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** 告警通知节点回归测试：模板、渠道投递、重复抑制、失败提示。 */
class AlertExecutorTest {

    private static final class StubSources extends DataSourceService {
        private Map<String, Object> fields = Map.of();

        StubSources() {
            super(null, null, null, null);
        }

        void set(Map<String, Object> value) {
            this.fields = value;
        }

        @Override
        public Map<String, Object> connectionFields(String id) {
            return fields;
        }
    }

    private static AlertExecutor executor(StubSources sources) {
        return new AlertExecutor(sources);
    }

    private static NodeExecutor.ExecResult run(AlertExecutor executor, Map<String, Object> config,
                                                List<Map<String, Object>> input) {
        GraphNode node = new GraphNode("n1", "output", "alert", "1", "告警", Map.of(), config);
        return executor.execute(new NodeExecutor.ExecContext("E1", "F1", node, Map.of()), config, input);
    }

    private static Map<String, Object> row(NodeExecutor.ExecResult result) {
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.output().get(0);
        return value;
    }

    @Test
    void rendersTemplatesFromInputAndVariables() {
        Map<String, Object> input = Map.of("device", "PT101", "temp", 95);
        assertEquals("【告警】PT101 温度 95℃，阈值 85℃",
                AlertExecutor.render("【告警】{{ $json.device }} 温度 {{ $json.temp }}℃，阈值 {{ $var.LIMIT }}℃",
                        input, Map.of("LIMIT", 85)));
        assertTrue(AlertExecutor.render("{{ $json }}", input, Map.of()).contains("PT101"));
        assertEquals("", AlertExecutor.render("{{ $json.missing }}", input, Map.of()));
    }

    @Test
    void keepsSimulationForPlaceholderTargets() {
        Map<String, Object> config = Map.of("channel", "企业微信",
                "webhook", "https://qyapi.weixin.qq.com/请填写");
        Map<String, Object> result = row(run(executor(new StubSources()), config,
                List.of(Map.of("event", "trigger"))));
        assertEquals("written", result.get("status"));
        assertEquals("simulated", result.get("mode"));
    }

    @Test
    void postsWebhookPayloadAndSuppressesRepeatedAlerts() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"errcode\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            AlertExecutor executor = executor(new StubSources());
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("channel", "Webhook");
            config.put("webhook", url);
            config.put("titleTemplate", "告警 {{ $json.device }}");
            config.put("messageTemplate", "温度 {{ $json.temp }}℃");
            config.put("severity", "严重");
            config.put("suppressMinutes", 10);
            List<Map<String, Object>> input = List.of(Map.of("device", "PT101", "temp", 95));

            assertEquals("sent", row(run(executor, config, input)).get("status"));
            assertEquals("suppressed", row(run(executor, config, input)).get("status"));
            assertEquals(1, requests.size());
            assertTrue(requests.get(0).contains("告警 PT101"));
            assertTrue(requests.get(0).contains("温度 95℃"));

            config.put("channel", "企业微信");
            assertEquals("sent", row(run(executor, config, input)).get("status"));
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("\"msgtype\":\"text\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsRealEmailThroughSmtp() throws Exception {
        try (SmtpServer smtp = new SmtpServer()) {
            StubSources sources = new StubSources();
            Map<String, Object> cred = new LinkedHashMap<>();
            cred.put("smtpHost", "127.0.0.1");
            cred.put("smtpPort", String.valueOf(smtp.port()));
            cred.put("encryption", "无");
            cred.put("username", "");
            cred.put("password", "");
            cred.put("from", "alert@tonglink.test");
            sources.set(cred);

            Map<String, Object> config = new LinkedHashMap<>();
            config.put("channel", "邮件");
            config.put("webhook", "recipient@example.com");
            config.put("titleTemplate", "Alert PT101");
            config.put("messageTemplate", "device=PT101 temp=95");
            config.put("severity", "警告");
            config.put("suppressMinutes", 0);
            Map<String, Object> result = row(run(executor(sources), config,
                    List.of(Map.of("device", "PT101", "temp", 95))));
            assertEquals("sent", result.get("status"));
            assertEquals("real", result.get("mode"));
            assertFalse(smtp.messages.isEmpty());
            String mail = smtp.messages.get(0);
            assertTrue(mail.contains("To: recipient@example.com"), mail);
            // 主题按 MIME Q 编码，空格编码为下划线
            assertTrue(mail.contains("Alert_PT101"), mail);
            String encodedBody = mail.substring(mail.indexOf("Content-Transfer-Encoding: base64")
                    + "Content-Transfer-Encoding: base64".length()).replaceAll("\\s", "");
            String body = new String(java.util.Base64.getMimeDecoder().decode(encodedBody),
                    StandardCharsets.UTF_8);
            assertTrue(body.contains("device=PT101 temp=95"), body);
        }
    }

    @Test
    void reportsClearErrorWhenSmtpIsUnreachable() throws Exception {
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            closedPort = probe.getLocalPort();
        }
        StubSources sources = new StubSources();
        sources.set(Map.of("smtpHost", "127.0.0.1", "smtpPort", String.valueOf(closedPort),
                "encryption", "无", "username", "", "password", "", "from", "alert@tonglink.test"));
        Map<String, Object> config = Map.of("channel", "邮件", "webhook", "recipient@example.com",
                "suppressMinutes", 0);
        ApiException error = assertThrows(ApiException.class,
                () -> run(executor(sources), config, List.of(Map.of("event", "trigger"))));
        assertTrue(error.getMessage().startsWith("邮件告警发送失败"), error.getMessage());
    }

    /** 最小 SMTP 服务端：够 Jakarta Mail 走完 EHLO / MAIL / RCPT / DATA / QUIT。 */
    private static final class SmtpServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private volatile boolean running = true;

        SmtpServer() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = Thread.ofPlatform().daemon(true).start(this::serve);
        }

        int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (running) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException e) {
                    if (running) throw new RuntimeException(e);
                }
            }
        }

        private void handle(Socket socket) throws IOException {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            OutputStream out = socket.getOutputStream();
            write(out, "220 localhost ESMTP\r\n");
            String line;
            boolean data = false;
            StringBuilder message = new StringBuilder();
            while ((line = in.readLine()) != null) {
                if (data) {
                    if (".".equals(line)) {
                        messages.add(message.toString());
                        write(out, "250 OK\r\n");
                        data = false;
                    } else {
                        message.append(line).append("\r\n");
                    }
                    continue;
                }
                String command = line.toUpperCase(Locale.ROOT);
                if (command.startsWith("EHLO")) {
                    write(out, "250-localhost\r\n250 OK\r\n");
                } else if (command.startsWith("DATA")) {
                    write(out, "354 End data with <CR><LF>.<CR><LF>\r\n");
                    data = true;
                } else if (command.startsWith("QUIT")) {
                    write(out, "221 Bye\r\n");
                    break;
                } else {
                    write(out, "250 OK\r\n");
                }
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        @Override
        public void close() throws IOException {
            running = false;
            server.close();
        }
    }
}
