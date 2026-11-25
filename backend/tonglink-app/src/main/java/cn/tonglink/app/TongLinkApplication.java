/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.app;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口。
 *
 * <p>SQLite 轻量模式下，启动前先确保数据文件所在目录存在——SQLite 不会自动创建目录，
 * 缺少 {@code ./data} 会直接报 "path does not exist"，是本地第一次运行最常见的启动失败原因。
 * PostgreSQL 模式（DB_URL 以 jdbc:postgresql: 开头）由服务端管理数据目录，此处跳过。</p>
 */
@SpringBootApplication(scanBasePackages = "cn.tonglink")
@EnableScheduling
public class TongLinkApplication {

    public static void main(String[] args) {
        prepareDataDirectory();
        // Hikari 默认每 30 秒才补一次池；数据库抖动导致池空后，这个空窗正好等于
        // 借连接超时时间，定时任务会成片报 "Connection is not available"。
        // 提前改为 5 秒，让池在短暂不可达后快速恢复，且不影响既有 JVM 参数覆盖。
        if (System.getProperty("com.zaxxer.hikari.housekeeping.periodMs") == null) {
            System.setProperty("com.zaxxer.hikari.housekeeping.periodMs", "5000");
        }
        SpringApplication.run(TongLinkApplication.class, args);
    }

    /**
     * 创建 SQLite 数据库文件所在目录（PostgreSQL 模式跳过）。
     *
     * <p>优先级：环境变量 {@code DB_URL} &gt; {@code TONGLINK_DB_PATH} &gt; 默认
     * {@code ./data/tonglink.db}。只创建目录、不创建文件，文件由 JDBC 驱动在首次连接时生成。</p>
     */
    private static void prepareDataDirectory() {
        String dbUrl = firstNonBlank(System.getenv("DB_URL"), System.getenv("SPRING_DATASOURCE_URL"));
        if (dbUrl != null && !dbUrl.toLowerCase().startsWith("jdbc:sqlite:")) {
            return; // PostgreSQL 等服务端数据库，无需本地目录
        }
        String configured = System.getenv().getOrDefault("TONGLINK_DB_PATH", "./data/tonglink.db");
        try {
            Path database = Path.of(configured).toAbsolutePath();
            Path parent = database.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (Exception e) {
            System.err.println("警告：无法创建数据目录 " + configured + "，" + e.getMessage());
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
