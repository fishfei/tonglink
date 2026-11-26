/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.datasource.DataSourceService;
import cn.tonglink.datasource.IndustrialGateway;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.repository.MetaRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Modbus TCP 节点执行器：按节点配置对真实从站执行读 / 写。
 *
 * <p>连接参数来自数据源管理（host / port / unitId），节点 config 只描述读写意图
 * （operation / registerType / startAddress / count / value）。失败时不降级为模拟，
 * 直接抛异常让节点标记 FAILED，确保生产环境的写入结果可信。</p>
 */
@Component
public class ModbusExecutor implements NodeExecutor {

    private final MetaRepository meta;
    private final DataSourceService sources;
    private final IndustrialGateway gateway;

    public ModbusExecutor(MetaRepository meta, DataSourceService sources, IndustrialGateway gateway) {
        this.meta = meta;
        this.sources = sources;
        this.gateway = gateway;
    }

    @Override
    public Set<String> supports() {
        return Set.of("source:modbus");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String connectionId = String.valueOf(
                config.getOrDefault("connection", config.getOrDefault("datasource", "")));
        DataSource source = connectionId.isBlank() ? null : meta.source(connectionId);
        if (source == null) {
            throw new IllegalStateException("Modbus 节点未绑定可用的数据源");
        }
        Map<String, Object> fields = sources.connectionFields(connectionId);
        String operation = String.valueOf(config.getOrDefault("operation", "读取"));
        boolean write = "write".equalsIgnoreCase(operation) || "写入".equals(operation);
        List<Map<String, Object>> rows = write
                ? gateway.writeModbus(fields, config)
                : gateway.readModbus(fields, config);
        return new ExecResult(rows, null);
    }
}
