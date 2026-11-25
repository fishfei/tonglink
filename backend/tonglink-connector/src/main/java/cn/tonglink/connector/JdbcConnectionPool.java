/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 数据库节点的连接池。
 *
 * <p>每个「连接 URL + 账号 + 密码」组合对应一个 Hikari 池，节点执行结束只归还连接，
 * 不关闭物理连接。这样高频采集/写入不必反复握手，也不会在流量突增时把目标库的连接数打满。
 * 与通用节点运行时的做法一致：节点本身不持有长连接，连接生命周期由运行时统一托管。</p>
 *
 * <p>连接池按凭据串做键，密码变更后会自动创建新池；旧池在应用退出时统一关闭。</p>
 */
@Component
public class JdbcConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(JdbcConnectionPool.class);

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final int maxPoolSize;
    private final long maxLifetimeMs;
    private final long connectionTimeoutMs;

    public JdbcConnectionPool(
            @Value("${tonglink.jdbc.pool-size:8}") int maxPoolSize,
            @Value("${tonglink.jdbc.max-lifetime-ms:300000}") long maxLifetimeMs,
            @Value("${tonglink.jdbc.connection-timeout-ms:10000}") long connectionTimeoutMs) {
        this.maxPoolSize = Math.max(1, maxPoolSize);
        // Hikari 要求 maxLifetime > 30s；留出保活探测的空间，避免服务端提前回收连接
        this.maxLifetimeMs = Math.max(60_000L, maxLifetimeMs);
        this.connectionTimeoutMs = connectionTimeoutMs;
    }

    /** 从池里借一条连接，用完照常 close() 归还即可。 */
    public Connection borrow(String jdbcUrl, String username, String password) throws SQLException {
        String user = username == null ? "" : username;
        String pass = password == null ? "" : password;
        String key = jdbcUrl + "|" + user + "|" + pass;
        HikariDataSource ds = pools.computeIfAbsent(key, k -> create(jdbcUrl, user, pass));
        return ds.getConnection();
    }

    private HikariDataSource create(String jdbcUrl, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("tl-node-" + pools.size());
        cfg.setJdbcUrl(jdbcUrl);
        if (!username.isBlank()) {
            cfg.setUsername(username);
        }
        if (!password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setValidationTimeout(Math.min(5_000L, connectionTimeoutMs));
        cfg.setIdleTimeout(Math.min(120_000L, maxLifetimeMs / 2));
        cfg.setMaxLifetime(maxLifetimeMs);
        // 30 秒保活探测：必须小于 maxLifetime，使被中间网络回收的空闲连接在使用前被替换
        cfg.setKeepaliveTime(Math.min(30_000L, maxLifetimeMs / 2));
        cfg.setInitializationFailTimeout(-1L);
        cfg.setLeakDetectionThreshold(60_000L);
        if (jdbcUrl != null && jdbcUrl.toLowerCase().contains("postgresql")) {
            cfg.addDataSourceProperty("tcpKeepAlive", "true");
        }
        HikariDataSource ds = new HikariDataSource(cfg);
        log.info("已为 {} 创建节点连接池（最大 {} 条）", jdbcUrl, maxPoolSize);
        return ds;
    }

    /** 当前活跃池数量，供健康检查与诊断使用。 */
    public int poolCount() {
        return pools.size();
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
