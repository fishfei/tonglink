/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.repository;

import cn.tonglink.model.entity.AiSettings;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * AI 设置仓储：每个厂商一行，行 id 就是 provider，避免各厂商共用同一份 API Key。
 *
 * <p>另有一行保留 id {@code __active__} 记录当前启用的引擎，其余列留空。
 * JdbcTemplate 手写 SQL 同时兼容 SQLite / PostgreSQL。</p>
 */
@Repository
public class AiSettingsRepository {

    /** 记录当前启用引擎的保留行，不参与厂商配置列表。 */
    private static final String ACTIVE_ID = "__active__";
    private static final String LOCAL = "local";

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

    /** 读取某个厂商的设置；从未保存过返回 null。 */
    public AiSettings settings(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        return jdbc.query("SELECT * FROM ai_settings WHERE id=?", ROW, provider)
                .stream().findFirst().orElse(null);
    }

    /** 所有厂商的设置（不含生效标记行）。 */
    public List<AiSettings> allSettings() {
        return jdbc.query("SELECT * FROM ai_settings WHERE id<>? ORDER BY id", ROW, ACTIVE_ID);
    }

    /** 插入或更新指定厂商的设置。 */
    public void save(AiSettings settings) {
        String now = LocalDateTime.now().withNano(0).toString();
        int updated = jdbc.update("""
                UPDATE ai_settings SET provider=?, endpoint=?, api_key_cipher=?, model=?, updated_at=?
                WHERE id=?
                """, settings.provider(), settings.endpoint(), settings.apiKeyCipher(),
                settings.model(), now, settings.id());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO ai_settings(id, provider, endpoint, api_key_cipher, model, updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, settings.id(), settings.provider(), settings.endpoint(),
                    settings.apiKeyCipher(), settings.model(), now);
        }
    }

    /** 当前启用的引擎标识；未设置时返回 local。 */
    public String activeProvider() {
        return jdbc.query("SELECT provider FROM ai_settings WHERE id=?",
                        (rs, i) -> rs.getString(1), ACTIVE_ID)
                .stream().findFirst().orElse(LOCAL);
    }

    /** 设置当前启用的引擎。 */
    public void setActiveProvider(String provider) {
        String now = LocalDateTime.now().withNano(0).toString();
        String value = provider == null || provider.isBlank() ? LOCAL : provider;
        int updated = jdbc.update("""
                UPDATE ai_settings SET provider=?, updated_at=? WHERE id=?
                """, value, now, ACTIVE_ID);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO ai_settings(id, provider, endpoint, api_key_cipher, model, updated_at)
                    VALUES(?,?,?,?,?,?)
                    """, ACTIVE_ID, value, null, null, null, now);
        }
    }

    /** 删除某个厂商的设置。 */
    public void clear(String provider) {
        jdbc.update("DELETE FROM ai_settings WHERE id=?", provider);
    }

    /** 清空全部厂商配置及生效标记，回到未配置状态。 */
    public void clearAll() {
        jdbc.update("DELETE FROM ai_settings");
    }
}
