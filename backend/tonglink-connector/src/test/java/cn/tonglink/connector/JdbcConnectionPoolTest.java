/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** 数据库节点连接池：同一连接串应复用同一个池。 */
class JdbcConnectionPoolTest {

    @Test
    void sameJdbcUrlReusesPool() throws Exception {
        Path db = Files.createTempFile("tl-pool-test-", ".db");
        String url = "jdbc:sqlite:" + db.toAbsolutePath();
        JdbcConnectionPool pool = new JdbcConnectionPool(4, 1_800_000L, 10_000L);
        try {
            try (Connection c = pool.borrow(url, "", ""); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS t (id INTEGER)");
            }
            try (Connection c = pool.borrow(url, "", ""); Statement s = c.createStatement()) {
                s.execute("INSERT INTO t VALUES (1)");
            }
            assertEquals(1, pool.poolCount());
        } finally {
            pool.closeAll();
            Files.deleteIfExists(db);
        }
    }

    @Test
    void differentCredentialsCreateSeparatePools() throws Exception {
        JdbcConnectionPool pool = new JdbcConnectionPool(2, 1_800_000L, 10_000L);
        try {
            String url = "jdbc:sqlite::memory:";
            pool.borrow(url, "a", "1").close();
            pool.borrow(url, "b", "2").close();
            assertTrue(pool.poolCount() >= 2);
        } finally {
            pool.closeAll();
        }
    }
}
