/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.repository.MetaRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
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
 * JDBC 节点执行器：数据库查询/写入与时序库写入。
 *
 * <p>SQL 由节点配置拼装（表名、字段、WHERE、自定义语句）。这类拼接在受控的企业内网工具中
 * 是必要的灵活性，但必须提醒：节点配置等同 SQL 权限，应通过只读账号与字段白名单收敛风险。</p>
 *
 * <p>轻量模式下的降级策略：示例数据源指向内网地址（如 {@code 10.20.x.x}），本机不可达时
 * 返回带 {@code simulated} 标记的模拟结果，使演示流程可以跑通；真实可达的库则走真实执行。</p>
 */
@Component
public class JdbcExecutor implements NodeExecutor {

    private static final Logger log = LoggerFactory.getLogger(JdbcExecutor.class);

    private final MetaRepository meta;

    public JdbcExecutor(MetaRepository meta) {
        this.meta = meta;
    }

    @Override
    public Set<String> supports() {
        return Set.of("source:mysql", "source:oracle", "source:postgres", "source:sqlserver",
                "output:timeseries");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        // 时序输出节点没有 operation 字段，但语义就是写入，默认按 insert 处理
        String subtype = context.node() == null ? "" : context.node().subtype();
        String defaultOperation = "timeseries".equals(subtype) ? "insert" : "select";
        String operation = String.valueOf(config.getOrDefault("operation", defaultOperation));
        String sql = buildSql(operation, config, input);
        String connectionId = String.valueOf(
                config.getOrDefault("connection", config.getOrDefault("datasource", "")));

        DataSource source = connectionId.isBlank() ? null : meta.source(connectionId);
        if (source == null) {
            return simulated(context, operation, sql, "未绑定可用的数据源");
        }
        Map<String, Object> conn = JsonUtil.parse(source.configJson(),
                new TypeReference<Map<String, Object>>() {
                });
        String jdbcUrl = conn == null ? "" : String.valueOf(conn.getOrDefault("jdbcUrl", ""));
        if (jdbcUrl.isBlank()) {
            return simulated(context, operation, sql, "数据源未配置 jdbcUrl");
        }

        try (Connection c = connect(jdbcUrl, conn);
             Statement statement = c.createStatement()) {
            statement.setQueryTimeout(30);
            boolean hasResultSet = statement.execute(sql);
            if (hasResultSet) {
                return new ExecResult(readRows(statement.getResultSet()), null);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("affected", statement.getUpdateCount());
            row.put("sql", sql);
            return new ExecResult(List.of(row), null);
        } catch (Exception e) {
            // 演示流程可降级；测试/生产流程把 simulateOnError 设为 false，让失败真实暴露
            boolean simulate = Boolean.parseBoolean(
                    String.valueOf(config.getOrDefault("simulateOnError", "true")));
            log.warn("JDBC 节点执行失败{}：{}（{}）", simulate ? "，降级为模拟" : "", sql, e.getMessage());
            if (!simulate) {
                throw new IllegalStateException("数据库写入失败：" + e.getMessage());
            }
            return simulated(context, operation, sql, e.getMessage());
        }
    }

    /** 建立连接；账号密码优先取数据源配置。 */
    private Connection connect(String jdbcUrl, Map<String, Object> conn) throws Exception {
        String username = String.valueOf(conn.getOrDefault("username", ""));
        String password = String.valueOf(conn.getOrDefault("password", ""));
        return username.isBlank()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, username, password);
    }

    /** 把 ResultSet 转成行列表。 */
    private List<Map<String, Object>> readRows(ResultSet rs) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metaData = rs.getMetaData();
        int columns = metaData.getColumnCount();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columns; i++) {
                row.put(metaData.getColumnLabel(i), rs.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * 模拟结果：结构与真实查询一致（写入类返回 affected，查询类返回若干行），
     * 并显式标注 {@code simulated=true} 与原因，便于排查「为什么数据看起来是假的」。
     */
    private ExecResult simulated(ExecContext context, String operation, String sql, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("simulated", true);
        row.put("reason", reason);
        row.put("sql", sql == null ? "" : sql.substring(0, Math.min(500, sql.length())));
        row.put("ts", LocalDateTime.now().withNano(0).toString());
        if ("select".equalsIgnoreCase(operation) || "execute".equalsIgnoreCase(operation)) {
            row.put("rows", List.of(Map.of("id", 1, "status", "DEMO", "note", "本地模拟数据")));
        } else {
            row.put("affected", 1);
        }
        return new ExecResult(List.of(row), null);
    }

    /**
     * 按操作类型拼装 SQL。
     *
     * @param operation select / insert / update / delete / execute
     */
    private String buildSql(String operation, Map<String, Object> config,
                            List<Map<String, Object>> input) {
        String table = String.valueOf(config.getOrDefault("table", "plant.events"));
        String columns = String.valueOf(config.getOrDefault("columns", "*"));
        String where = String.valueOf(config.getOrDefault("where", "1=1"));
        if ("null".equals(where) || where.isBlank()) {
            where = "1=1";
        }
        return switch (operation.toLowerCase()) {
            case "insert" -> insertSql(table, columns, config, input);
            case "update" -> "UPDATE " + table + " SET " + valuesToSet(config.get("values"))
                    + " WHERE " + where;
            case "delete" -> "DELETE FROM " + table + " WHERE " + where;
            case "execute" -> String.valueOf(config.getOrDefault("sql", "SELECT 1"));
            default -> "SELECT " + columns + " FROM " + table + " WHERE " + where;
        };
    }

    /**
     * 生成 INSERT 语句。
     *
     * <p>两种模式：配置了固定 values 时写单行（兼容旧流程）；未配置 values 且有上游输入时，
     * 按 columns 把每一条输入行写成一批 VALUES。天气入库这类场景必须用后一种模式，
     * 否则 MySQL 节点只会重复写入配置里的静态占位值。</p>
     */
    private String insertSql(String table, String columns, Map<String, Object> config,
                             List<Map<String, Object>> input) {
        Map<String, Object> fixed = asMap(config.get("values"));
        if (fixed != null && !fixed.isEmpty()) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES (" + valuesClause(config) + ")";
        }
        if (input == null || input.isEmpty()) {
            return "INSERT INTO " + table + " (" + columns + ") VALUES ('demo')";
        }
        List<String> cols = insertColumns(columns, input);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table)
                .append(" (").append(String.join(",", cols)).append(") VALUES ");
        int max = Math.min(input.size(), 500);
        for (int i = 0; i < max; i++) {
            if (i > 0) sql.append(',');
            sql.append('(');
            for (int j = 0; j < cols.size(); j++) {
                if (j > 0) sql.append(',');
                sql.append(literal(input.get(i).get(cols.get(j))));
            }
            sql.append(')');
        }
        return sql.toString();
    }

    /** INSERT 字段列表：优先用配置，未配置时取第一条输入的全部字段。 */
    private List<String> insertColumns(String columns, List<Map<String, Object>> input) {
        if (columns == null || columns.isBlank() || "*".equals(columns)) {
            return input.isEmpty() ? List.of() : new ArrayList<>(input.get(0).keySet());
        }
        return java.util.Arrays.stream(columns.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** 把值转成 SQL 字面量；数字/布尔不加引号，null 写 NULL，字符串做转义。 */
    private String literal(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    /** 生成 INSERT 的 VALUES 片段。 */
    private String valuesClause(Map<String, Object> config) {
        Map<String, Object> values = asMap(config.get("values"));
        if (values == null || values.isEmpty()) {
            return "'demo'";
        }
        return String.join(",", values.values().stream()
                .map(v -> "'" + String.valueOf(v).replace("'", "''") + "'")
                .toList());
    }

    /** 生成 UPDATE 的 SET 片段。 */
    private String valuesToSet(Object raw) {
        Map<String, Object> values = asMap(raw);
        if (values == null || values.isEmpty()) {
            return "updated_at = CURRENT_TIMESTAMP";
        }
        return String.join(",", values.entrySet().stream()
                .map(e -> e.getKey() + "='" + String.valueOf(e.getValue()).replace("'", "''") + "'")
                .toList());
    }

    /** 配置里的写入值既可能是对象，也可能是 JSON 文本。 */
    private Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?>) {
            return JsonUtil.toMap(raw);
        }
        String text = String.valueOf(raw);
        if (text.startsWith("{") && text.endsWith("}")) {
            try {
                return JsonUtil.parse(text, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
