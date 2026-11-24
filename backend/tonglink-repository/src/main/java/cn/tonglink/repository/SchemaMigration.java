/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 跨库增量列迁移（SQLite / PostgreSQL 通用）。
 *
 * <p>{@code schema.sql} 使用 {@code CREATE TABLE IF NOT EXISTS}，老库不会自动获得新增列；
 * 本工具在每个 Repository 构造时执行一次幂等 ALTER，保证旧数据库升级后仍可直接启动。</p>
 *
 * <p>与旧版 {@code SqliteSchemaMigration} 靠「ALTER 抛异常即忽略」不同，这里通过 JDBC
 * {@link java.sql.DatabaseMetaData} 先判断列是否存在，再决定是否 ALTER。这样在 PostgreSQL
 * 下不会因为重复 ALTER 产生事务中断风险，两种数据库的日志也更清晰。</p>
 */
final class SchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);
    private static final Set<Integer> MIGRATED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SchemaMigration() {
    }

    static void ensure(JdbcTemplate jdbc) {
        if (!MIGRATED.add(System.identityHashCode(jdbc))) {
            return;
        }
        addColumn(jdbc, "flow_definition", "published_graph_json", "TEXT");
        addColumn(jdbc, "execution_node", "node_title", "VARCHAR(256)");
        addColumn(jdbc, "execution", "tags", "TEXT");
        // 老库中已发布流程补上快照，避免升级后执行引擎退回草稿图
        jdbc.update("""
                UPDATE flow_definition SET published_graph_json = graph_json
                WHERE (published_graph_json IS NULL OR published_graph_json = '')
                  AND published_version > 0 AND status = 'active'
                """);
    }

    private static void addColumn(JdbcTemplate jdbc, String table, String column, String type) {
        try {
            if (columnExists(jdbc, table, column)) {
                log.debug("迁移跳过：{}.{} 已存在", table, column);
                return;
            }
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("迁移：已为 {} 增加列 {}", table, column);
        } catch (Exception e) {
            // 并发启动时另一个实例可能已加列；以「列最终存在」为准，不因竞态失败
            log.debug("迁移：{} 增加列 {} 时忽略异常（可能已存在）", table, column, e);
        }
    }

    /** 用 JDBC 元数据判断列是否存在，SQLite 与 PostgreSQL 均支持。 */
    private static boolean columnExists(JdbcTemplate jdbc, String table, String column) {
        Boolean exists = jdbc.execute((Connection con) -> {
            try (ResultSet rs = con.getMetaData().getColumns(null, null, table, column)) {
                return rs.next();
            }
        });
        return Boolean.TRUE.equals(exists);
    }
}
