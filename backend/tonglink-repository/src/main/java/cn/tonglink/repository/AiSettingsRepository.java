/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.model.entity.AiSettings;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * AI 设置仓储：单行记录，JdbcTemplate 手写 SQL 兼容 SQLite / PostgreSQL。
 */
@Repository
public class AiSettingsRepository {

    private static final String DEFAULT_ID = "default";

    private static final RowMapper<AiSettings> ROW = (rs, i) -> new AiSettings(
            rs.getString("id"),
            rs.getString("provider"),
            rs.getString("endpoint"),
            rs.getString("api_key_cipher"),
            rs.getString("model"),
            rs.getString("updated_at"));

    private final JdbcTemplate jdbc;

    public AiSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 读取设置；从未保存过返回 null。 */
    public AiSettings settings() {
        return jdbc.query("SELECT * FROM ai_settings WHERE id=?", ROW, DEFAULT_ID)
                .stream().findFirst().orElse(null);
    }

    /** 插入或更新单行设置。 */
    public void save(AiSettings settings) {
        String now = LocalDateTime.now().withNano(0).toString();
        int updated = jdbc.update("""
                UPDATE ai_settings SET provider=?, endpoint=?, api_key_cipher=?, model=?, updated_at=?
                WHERE id=?
                """, settings.provider(), settings.endpoint(), settings.apiKeyCipher(),
                settings.model(), now, DEFAULT_ID);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO ai_settings(id, provider, endpoint, api_key_cipher, model, updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, DEFAULT_ID, settings.provider(), settings.endpoint(),
                    settings.apiKeyCipher(), settings.model(), now);
        }
    }

    /** 删除设置，恢复为「未配置」状态（回退环境变量 / 本地规则引擎）。 */
    public void clear() {
        jdbc.update("DELETE FROM ai_settings WHERE id=?", DEFAULT_ID);
    }
}
