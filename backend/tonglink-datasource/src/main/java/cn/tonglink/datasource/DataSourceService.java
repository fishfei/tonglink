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
import com.fasterxml.jackson.core.type.TypeReference;
import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 数据源业务逻辑：增删改查与连通性测试。
 *
 * <p>连通性测试遵循「真实可测就真测，不可测就校验配置」的原则：
 * HTTP 发 HEAD 请求、JDBC 建连接，工业协议（OPC UA / Modbus 等）在无现场设备时
 * 只校验参数完整性并明确提示需在现场验证——比返回一个假的「成功」更诚实。</p>
 */
@Service
public class DataSourceService {

    /** 这些字段属于数据源本身，其余字段都视为协议连接参数。 */
    private static final List<String> RESERVED = List.of(
            "id", "name", "category", "protocol", "status", "lastTest", "createdAt", "updatedAt", "config");

    private final MetaRepository repo;

    public DataSourceService(MetaRepository repo) {
        this.repo = repo;
    }

    /** 全部数据源。 */
    public List<DataSource> list() {
        return repo.sources();
    }

    /**
     * 按 ID 取数据源。
     *
     * @throws ApiException 不存在时返回 404
     */
    public DataSource get(String id) {
        DataSource s = repo.source(id);
        if (s == null) {
            throw new ApiException(Codes.NOT_FOUND, "数据源不存在");
        }
        return s;
    }

    /** 新建数据源。 */
    public DataSource create(Map<String, Object> body) {
        String id = str(body.get("id"), IdUtil.prefix("DS"));
        String now = repo.now();
        DataSource s = new DataSource(id,
                required(body, "name"),
                str(body.get("category"), "IT"),
                required(body, "protocol"),
                JsonUtil.stringify(configOf(body)),
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
        DataSource s = new DataSource(id,
                required(body, "name"),
                str(body.get("category"), old.category()),
                required(body, "protocol"),
                JsonUtil.stringify(configOf(body)),
                str(body.get("status"), old.status()),
                old.lastTest(),
                old.createdAt(),
                repo.now());
        repo.updateSource(s);
        return s;
    }

    /** 删除数据源。 */
    public void delete(String id) {
        get(id);
        repo.deleteSource(id);
    }

    /**
     * 测试连通性，并把结果回写到 {@code status} 与 {@code lastTest}。
     *
     * @return {@code {success, message, testedAt}}
     */
    public Map<String, Object> test(String id) {
        DataSource s = get(id);
        Map<String, Object> config = JsonUtil.parse(s.configJson(), new TypeReference<Map<String, Object>>() {
        });
        if (config == null) {
            config = Map.of();
        }
        boolean ok;
        String message;
        try {
            String protocol = s.protocol().toLowerCase();
            if (protocol.contains("http")) {
                ok = testHttp(str(config.get("url"), ""));
                message = ok ? "HTTP 探测成功（目标可达）" : "HTTP 探测失败：地址不可达或返回 5xx";
            } else if (protocol.contains("sql") || protocol.contains("mysql")
                    || protocol.contains("oracle") || protocol.contains("postgres")
                    || protocol.contains("mongo")) {
                ok = testJdbc(str(config.get("jdbcUrl"), ""), str(config.get("username"), ""),
                        str(config.get("password"), ""));
                message = ok ? "JDBC 连接成功" : "JDBC 连接失败：请检查地址、账号或驱动是否在 classpath";
            } else {
                ok = !config.isEmpty();
                message = ok ? "配置校验通过（工业协议真实连通性需在现场网络验证）" : "缺少连接参数";
            }
        } catch (Exception e) {
            ok = false;
            message = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        String now = repo.now();
        DataSource updated = new DataSource(s.id(), s.name(), s.category(), s.protocol(), s.configJson(),
                ok ? "connected" : "error", now, s.createdAt(), now);
        repo.updateSource(updated);
        return Map.of("success", ok, "message", message, "testedAt", now);
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

    /**
     * 提取协议连接参数。
     *
     * <p>前端新版本传 {@code config} 对象；旧版或导入文件可能把参数平铺在顶层，
     * 这里两种都兼容。</p>
     */
    private Map<String, Object> configOf(Map<String, Object> body) {
        Object config = body.get("config");
        if (config instanceof Map) {
            return JsonUtil.toMap(config);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        body.forEach((k, v) -> {
            if (!RESERVED.contains(k)) {
                result.put(k, v);
            }
        });
        return result;
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String required(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new ApiException(Codes.VALIDATION, "缺少必填字段：" + key);
        }
        return String.valueOf(value);
    }
}
