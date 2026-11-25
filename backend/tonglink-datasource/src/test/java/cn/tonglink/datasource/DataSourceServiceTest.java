/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DataSourceServiceTest {

    /** 编辑态密码只回掩码：测试连接时必须还原已保存的真实密码，否则鉴权必然失败。 */
    @Test
    void 掩码密码还原为已保存值() {
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("host", "db.example.com");
        incoming.put("username", "root");
        incoming.put("password", "******");
        incoming.put("appSecret", "");
        Map<String, Object> stored = Map.of("password", "test-secret", "appSecret", "real-secret");

        Map<String, Object> resolved = DataSourceService.resolveMaskedSecrets(
                incoming, stored, List.of("password", "appSecret"));

        assertThat(resolved.get("password")).isEqualTo("test-secret");
        assertThat(resolved.get("appSecret")).isEqualTo("real-secret");
        assertThat(resolved.get("host")).isEqualTo("db.example.com");
    }

    /** 用户新填的明文密码不能被覆盖。 */
    @Test
    void 明文新密码保持不变() {
        Map<String, Object> incoming = new LinkedHashMap<>();
        incoming.put("password", "new-password");
        Map<String, Object> stored = Map.of("password", "old-password");

        Map<String, Object> resolved = DataSourceService.resolveMaskedSecrets(
                incoming, stored, List.of("password"));

        assertThat(resolved.get("password")).isEqualTo("new-password");
    }
}
