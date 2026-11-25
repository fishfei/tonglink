/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.repository.MetaRepository;
import cn.tonglink.security.CredentialTypeRegistry;
import cn.tonglink.security.CryptoService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 数据源业务逻辑：连接参数与认证凭据统一维护，并提供连通性测试。
 *
 * <p>凭据不再是独立功能：每个数据源可以带一份结构化凭据，按 {@code credentialType}
 * 渲染不同表单。密码类字段以 AES-GCM 密文落库，接口只回掩码；
 * 执行节点通过 {@link #credentialFields(String)} 拿到解密后的字段。</p>
 */
@Service
public class DataSourceService {

    private final MetaRepository repo;
    private final CryptoService crypto;
    private final CredentialTypeRegistry credentialTypes;

    public DataSourceService(MetaRepository repo, CryptoService crypto,
                             CredentialTypeRegistry credentialTypes) {
        this.repo = repo;
        this.crypto = crypto;
        this.credentialTypes = credentialTypes;
    }

    /** 全部数据源。 */
    public List<DataSource> list() {
        return repo.sources();
    }

    /** 按 ID 取数据源，不存在时返回 404。 */
    public DataSource get(String id) {
        DataSource s = repo.source(id);
        if (s == null) {
            throw new ApiException(Codes.NOT_FOUND, "数据源不存在：" + id);
        }
        return s;
    }

    /** 新建数据源：类别与协议由所选类型唯一决定，不再让用户重复填写。 */
    public DataSource create(Map<String, Object> body) {
        String id = str(body.get("id"), IdUtil.prefix("DS"));
        String now = repo.now();
        String credentialType = required(body, "credentialType");
        Map<String, Object> fields = credentialFieldsOf(body);
        validateFields(credentialType, fields);
        String credentialJson = encodeCredential(fields, Map.of(), credentialType);
        DataSource s = new DataSource(id,
                required(body, "name"),
                credentialTypes.category(credentialType),
                credentialTypes.protocol(credentialType, fields),
                JsonUtil.stringify(configOf(body)),
                credentialType,
                credentialJson,
                str(body.get("status"), "connected"),
                now,
                now,
                now);
        repo.saveSource(s);
        return s;
    }

    /** 更新数据源；保留创建时间与最近测试时间。 */
    public DataSource update(String id, Map<String, Object> body) {
        DataSource old = get(id);
        String credentialType = body.containsKey("credentialType")
                ? str(body.get("credentialType"), "")
                : nullToEmpty(old.credentialType());
        Map<String, Object> fields = credentialFieldsOf(body);
        validateFields(credentialType, fields);
        String credentialJson = encodeCredential(fields, parseFields(old.credentialJson()), credentialType);
        DataSource s = new DataSource(id,
                required(body, "name"),
                credentialTypes.category(credentialType),
                credentialTypes.protocol(credentialType, fields),
                JsonUtil.stringify(configOf(body)),
                credentialType,
                credentialJson,
                str(body.get("status"), old.status()),
                old.lastTest(),
                old.createdAt(),
                repo.now());
        repo.updateSource(s);
        return s;
    }

    /** 删除数据源，节点中引用它的配置会失去凭据并明确报错。 */
    public void delete(String id) {
        get(id);
        repo.deleteSource(id);
    }

    /**
     * 取数据源中解密后的凭据字段，供执行器使用；没有凭据时返回空 Map。
     */
    public Map<String, Object> credentialFields(String id) {
        if (id == null || id.isBlank()) {
            return Map.of();
        }
        DataSource s = repo.source(id);
        if (s == null || s.credentialType() == null || s.credentialType().isBlank()
                || s.credentialJson() == null || s.credentialJson().isBlank()) {
            return Map.of();
        }
        Map<String, Object> stored = parseFields(s.credentialJson());
        Map<String, Object> result = new LinkedHashMap<>(stored);
        for (String secret : credentialTypes.secretFields(s.credentialType())) {
            Object raw = stored.get(secret);
            if (raw != null && !String.valueOf(raw).isBlank()) {
                result.put(secret, crypto.decrypt(String.valueOf(raw)));
            }
        }
        return result;
    }

    /**
     * 数据源的完整连接字段：类型表单里的连接/认证参数优先，再叠加节点专用附加参数。
     * 执行器统一从这里取，避免各自去解析 config_json 与 credential_json。
     */
    public Map<String, Object> connectionFields(String id) {
        DataSource s = repo.source(id);
        if (s == null) {
            return Map.of();
        }
        return connectionFieldsOf(parseFields(s.configJson()), nullToEmpty(s.credentialType()),
                credentialFields(id));
    }

    /** 合并连接字段，并为数据库类型生成完整 JDBC URL；显式填写的 URL 优先。 */
    private Map<String, Object> connectionFieldsOf(Map<String, Object> config, String credentialType,
                                                   Map<String, Object> credentials) {
        Map<String, Object> merged = new LinkedHashMap<>(config == null ? Map.of() : config);
        merged.putAll(credentials);
        if ("database".equals(credentialType) && str(merged.get("jdbcUrl"), "").isBlank()) {
            String driver = str(merged.get("driver"), "MySQL");
            String host = str(merged.get("host"), "");
            if (!host.isBlank()) {
                String port = str(merged.get("port"), defaultPort(driver));
                // 数据库名可留空：MySQL/PostgreSQL 允许只连到实例，不指定默认库
                String db = str(merged.get("database"), "");
                merged.put("jdbcUrl", jdbcUrl(driver, host, port, db));
            }
        }
        return merged;
    }

    private static String defaultPort(String driver) {
        return switch (driver) {
            case "PostgreSQL" -> "5432";
            case "Oracle" -> "1521";
            case "SQL Server" -> "1433";
            default -> "3306";
        };
    }

    private static String jdbcUrl(String driver, String host, String port, String db) {
        // 数据库名（库名/服务名）可为空：允许只提供主机与账号连接实例，随后由 SQL 指定库
        String database = str(db, "");
        String slashDb = "/" + database;
        return switch (driver) {
            // MySQL 8 默认 caching_sha2_password：不带这些参数会出现 Public Key Retrieval is not allowed
            case "MySQL" -> "jdbc:mysql://" + host + ":" + port + slashDb
                    + "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
                    + "&connectTimeout=5000&socketTimeout=5000";
            case "PostgreSQL" -> "jdbc:postgresql://" + host + ":" + port + slashDb
                    + "?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true";
            case "Oracle" -> "jdbc:oracle:thin:@" + host + ":" + port
                    + (database.isBlank() ? "" : ":" + database);
            case "SQL Server" -> "jdbc:sqlserver://" + host + ":" + port
                    + (database.isBlank() ? "" : ";databaseName=" + database) + ";connectTimeout=5";
            default -> "jdbc:" + driver.toLowerCase() + "://" + host + ":" + port + slashDb;
        };
    }

    /** 数据源视图：连接参数原样返回，凭据里的密码字段只给掩码。 */

    public Map<String, Object> view(DataSource s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", s.id());
        out.put("name", s.name());
        out.put("category", s.category());
        out.put("protocol", s.protocol());
        out.put("config", parseFields(s.configJson()));
        out.put("configJson", nullToEmpty(s.configJson()));
        out.put("credentialType", nullToEmpty(s.credentialType()));
        out.put("credentialTypeLabel", String.valueOf(credentialTypes
                .type(nullToEmpty(s.credentialType())).getOrDefault("label", "")));
        Map<String, Object> fields = parseFields(s.credentialJson());
        Map<String, Object> safe = new LinkedHashMap<>();
        List<String> secrets = credentialTypes.secretFields(nullToEmpty(s.credentialType()));
        fields.forEach((k, v) -> safe.put(k, secrets.contains(k) && v != null
                && !String.valueOf(v).isBlank() ? "******" : v));
        out.put("credentialFields", safe);
        out.put("status", nullToEmpty(s.status()));
        out.put("lastTest", nullToEmpty(s.lastTest()));
        out.put("createdAt", nullToEmpty(s.createdAt()));
        out.put("updatedAt", nullToEmpty(s.updatedAt()));
        return out;
    }

    /** 凭据类型目录，供前端数据源抽屉按类型渲染差异化表单。 */
    public List<Map<String, Object>> credentialTypes() {
        return credentialTypes.list();
    }

    /**
     * 测试连通性，并把结果回写到 {@code status} 与 {@code lastTest}。
     */
    public Map<String, Object> test(String id) {
        DataSource s = get(id);
        Map<String, Object> fields = connectionFields(id);
        ProbeResult result = probe(nullToEmpty(s.protocol()), nullToEmpty(s.credentialType()), fields);
        String now = repo.now();
        DataSource updated = new DataSource(s.id(), s.name(), s.category(), s.protocol(), s.configJson(),
                s.credentialType(), s.credentialJson(), result.ok() ? "connected" : "error",
                now, s.createdAt(), now);
        repo.updateSource(updated);
        return Map.of("success", result.ok(), "message", result.message(), "testedAt", now);
    }

    /** 保存前测试：用抽屉里的表单字段直接探测，不落库、不改数据源状态。 */
    public Map<String, Object> testDraft(Map<String, Object> body) {
        String credentialType = required(body, "credentialType");
        Map<String, Object> fields = credentialFieldsOf(body);
        // 编辑态：密码字段只回掩码，测试时用已保存的解密值补回，否则会用 "******" 当密码去连库
        String editingId = str(body.get("id"), "");
        if (!editingId.isBlank()) {
            DataSource stored = repo.source(editingId);
            if (stored != null && credentialType.equals(nullToEmpty(stored.credentialType()))) {
                fields = resolveMaskedSecrets(fields, credentialFields(editingId),
                        credentialTypes.secretFields(credentialType));
            }
        }
        validateFields(credentialType, fields);
        Map<String, Object> config = body.get("config") instanceof Map<?, ?>
                ? JsonUtil.toMap(body.get("config")) : Map.of();
        Map<String, Object> merged = connectionFieldsOf(config, credentialType, fields);
        String protocol = credentialTypes.protocol(credentialType, fields);
        ProbeResult result = probe(protocol, credentialType, merged);
        return Map.of("success", result.ok(), "message", result.message());
    }

    /** 真正的探测逻辑：数据库连接类型始终走 JDBC，其他类型按协议区分。 */
    private ProbeResult probe(String protocol, String credentialType, Map<String, Object> fields) {
        try {
            if ("database".equals(credentialType)) {
                String url = str(fields.get("jdbcUrl"), "");
                if (url.isBlank()) {
                    return new ProbeResult(false,
                            "缺少 JDBC URL：请填写主机与数据库名，或在「完整 JDBC URL」中直接粘贴连接串");
                }
                try {
                    testJdbc(url, str(fields.get("username"), ""), str(fields.get("password"), ""));
                    return new ProbeResult(true, "JDBC 连接成功");
                } catch (Exception e) {
                    String detail = e.getMessage() == null || e.getMessage().isBlank()
                            ? e.toString() : e.getMessage();
                    if (detail.contains("No suitable driver")) {
                        detail = "缺少对应的 JDBC 驱动，请检查「数据库类型」是否选对";
                    }
                    return new ProbeResult(false, "JDBC 连接失败：" + detail);
                }
            }
            String p = nullToEmpty(protocol).toLowerCase();
            if (p.contains("http")) {
                String url = str(fields.get("baseUrl"), str(fields.get("url"), ""));
                boolean ok = testHttp(url);
                return new ProbeResult(ok, ok ? "HTTP 探测成功（目标可达）"
                        : "HTTP 探测失败：地址不可达或返回 5xx");
            }
            if (p.contains("sql") || p.contains("mysql") || p.contains("oracle")
                    || p.contains("postgres") || p.contains("mongo")) {
                String url = str(fields.get("jdbcUrl"), "");
                if (url.isBlank()) {
                    return new ProbeResult(false, "缺少 JDBC URL，请检查数据源配置");
                }
                try {
                    testJdbc(url, str(fields.get("username"), ""), str(fields.get("password"), ""));
                    return new ProbeResult(true, "JDBC 连接成功");
                } catch (Exception e) {
                    String detail = e.getMessage() == null || e.getMessage().isBlank()
                            ? e.toString() : e.getMessage();
                    return new ProbeResult(false, "JDBC 连接失败：" + detail);
                }
            }
            List<Map<String, Object>> defs = credentialTypes.type(credentialType)
                    .get("fields") instanceof List<?> list ? castFields(list) : List.of();
            List<String> missing = defs.stream()
                    .filter(f -> Boolean.TRUE.equals(f.get("required")))
                    .map(f -> String.valueOf(f.get("key")))
                    .filter(k -> str(fields.get(k), "").isBlank()).toList();
            if (!missing.isEmpty()) {
                return new ProbeResult(false, "缺少必填参数：" + missing);
            }
            return new ProbeResult(true, "连接配置校验通过（真实连通性需在现场网络验证）");
        } catch (Exception e) {
            String detail = e.getMessage() == null ? e.toString() : e.getMessage();
            return new ProbeResult(false, detail);
        }
    }

    private record ProbeResult(boolean ok, String message) {}

    /**
     * 编辑态草稿测试时把掩码或空白的密码字段替换成已保存的解密值。
     * 只替换 {@code secretFields} 声明的字段，用户新填的明文密码保持不变。
     */
    static Map<String, Object> resolveMaskedSecrets(Map<String, Object> incoming,
                                                    Map<String, Object> stored,
                                                    List<String> secrets) {
        Map<String, Object> result = new LinkedHashMap<>(incoming);
        for (String secret : secrets) {
            Object value = result.get(secret);
            boolean masked = value == null || String.valueOf(value).isBlank()
                    || "******".equals(String.valueOf(value));
            if (masked && stored.containsKey(secret)) {
                result.put(secret, stored.get(secret));
            }
        }
        return result;
    }

    /** HTTP 探测：用 HEAD 请求，避免对业务接口产生副作用。 */
    private boolean testHttp(String url) {
        if (url.isBlank()) {
            return false;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    /** JDBC 探测：3 秒内判定连接是否有效。 */
    private boolean testJdbc(String url, String user, String password) throws Exception {
        if (url.isBlank()) {
            return false;
        }
        try (Connection conn = user.isBlank()
                ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password)) {
            return conn.isValid(3);
        }
    }

    /** 节点专用附加参数，与类型表单里的连接/认证字段分开存放。 */
    private Map<String, Object> configOf(Map<String, Object> body) {
        return body.get("config") instanceof Map<?, ?> ? JsonUtil.toMap(body.get("config")) : Map.of();
    }

    /** 前端类型表单提交的连接与认证字段。 */
    private Map<String, Object> credentialFieldsOf(Map<String, Object> body) {
        if (!(body.get("credentialFields") instanceof Map<?, ?>)) {
            throw new ApiException(Codes.VALIDATION, "缺少 credentialFields");
        }
        return JsonUtil.toMap(body.get("credentialFields"));
    }

    /** 按类型定义校验必填项，避免保存后运行时才发现参数缺失。 */
    @SuppressWarnings("unchecked")
    private void validateFields(String type, Map<String, Object> fields) {
        Object defs = credentialTypes.type(type).get("fields");
        if (!(defs instanceof List<?> list)) {
            throw new ApiException(Codes.VALIDATION, "未知的数据源类型：" + type);
        }
        for (Object entry : list) {
            Map<String, Object> f = (Map<String, Object>) entry;
            if (Boolean.TRUE.equals(f.get("required"))
                    && str(fields.get(String.valueOf(f.get("key"))), "").isBlank()) {
                throw new ApiException(Codes.VALIDATION,
                        "缺少必填参数：" + f.get("label"));
            }
        }
        // 数据库连接：完整 URL 与「主机」二选一。MySQL/PostgreSQL 允许不指定库名即可连接实例；
        // Oracle 的服务名、SQL Server 的 databaseName 必需，仍要求填写。
        if ("database".equals(type) && str(fields.get("jdbcUrl"), "").isBlank()) {
            if (str(fields.get("host"), "").isBlank()) {
                throw new ApiException(Codes.VALIDATION,
                        "缺少必填参数：主机（或填写完整 JDBC URL）");
            }
            String driver = str(fields.get("driver"), "MySQL");
            if (("Oracle".equals(driver) || "SQL Server".equals(driver))
                    && str(fields.get("database"), "").isBlank()) {
                throw new ApiException(Codes.VALIDATION, "缺少必填参数：数据库名（服务名）");
            }
        }
        // OAuth 2.0 的 password 模式额外要求用户名与密码；其他模式只要求通用必填项。
        if ("oauth2".equals(type) && "password".equals(str(fields.get("grantType"), ""))
                && (str(fields.get("username"), "").isBlank() || str(fields.get("password"), "").isBlank())) {
            throw new ApiException(Codes.VALIDATION, "password 模式需要填写用户名与密码");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castFields(List<?> list) {
        return list.stream().map(f -> (Map<String, Object>) f).toList();
    }

    /** 把类型表单里的密码字段加密；编辑时掩码表示保留原值。 */
    private String encodeCredential(Map<String, Object> incoming, Map<String, Object> previous, String type) {
        List<String> secrets = credentialTypes.secretFields(type);
        Map<String, Object> stored = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : incoming.entrySet()) {
            Object value = entry.getValue();
            if (secrets.contains(entry.getKey())) {
                String text = value == null ? "" : String.valueOf(value);
                if (text.isBlank() || "******".equals(text)) {
                    Object kept = previous.get(entry.getKey());
                    value = kept;
                    text = kept == null ? "" : String.valueOf(kept);
                }
                // 已加密的值原样保留，避免二次加密
                stored.put(entry.getKey(), text.startsWith("v1:") ? text : crypto.encrypt(text));
            } else {
                stored.put(entry.getKey(), value);
            }
        }
        return JsonUtil.stringify(stored);
    }

    private static Map<String, Object> parseFields(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = JsonUtil.parse(json, new TypeReference<Map<String, Object>>() {});
        return parsed == null ? new LinkedHashMap<>() : parsed;
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ApiException(Codes.VALIDATION, "缺少必填字段：" + key);
        }
        return String.valueOf(value);
    }
}
