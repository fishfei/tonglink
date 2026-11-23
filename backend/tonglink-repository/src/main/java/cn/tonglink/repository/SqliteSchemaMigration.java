/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SQLite 轻量模式的增量列迁移。
 *
 * <p>{@code schema.sql} 使用 {@code CREATE TABLE IF NOT EXISTS}，老库不会自动获得新增列；
 * 本工具在每个 Repository 构造时执行一次幂等 ALTER，保证旧数据库升级后仍可直接启动。
 * 列已存在时 SQLite 会抛异常，这里按比例记录并忽略——这正是「列已在 schema.sql 中创建」的正常情况。</p>
 */
final class SqliteSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaMigration.class);
    private static final Set<Integer> MIGRATED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SqliteSchemaMigration() {
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
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("SQLite 迁移：已为 {} 增加列 {}", table, column);
        } catch (DataAccessException e) {
            log.debug("SQLite 迁移：{} 已存在列 {}（或无需迁移）", table, column);
        }
    }
}
