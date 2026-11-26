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
 * OPC UA 节点执行器：按节点配置对真实服务端点位执行读 / 写。
 *
 * <p>连接参数来自数据源管理（endpoint / namespace / 可选认证），节点 config 描述
 * operation（读取 / 写入）、nodeId 与写入 value。写入类型自动跟随点位现有类型，
 * 失败直接抛异常标记 FAILED，不做模拟降级。</p>
 */
@Component
public class OpcuaExecutor implements NodeExecutor {

    private final MetaRepository meta;
    private final DataSourceService sources;
    private final IndustrialGateway gateway;

    public OpcuaExecutor(MetaRepository meta, DataSourceService sources, IndustrialGateway gateway) {
        this.meta = meta;
        this.sources = sources;
        this.gateway = gateway;
    }

    @Override
    public Set<String> supports() {
        return Set.of("source:opcua");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String connectionId = String.valueOf(
                config.getOrDefault("connection", config.getOrDefault("datasource", "")));
        DataSource source = connectionId.isBlank() ? null : meta.source(connectionId);
        if (source == null) {
            throw new IllegalStateException("OPC UA 节点未绑定可用的数据源");
        }
        Map<String, Object> fields = sources.connectionFields(connectionId);
        String operation = String.valueOf(config.getOrDefault("operation", "读取"));
        boolean write = "write".equalsIgnoreCase(operation) || "写入".equals(operation);
        List<Map<String, Object>> rows = write
                ? gateway.writeOpcua(fields, config)
                : gateway.readOpcua(fields, config);
        return new ExecResult(rows, null);
    }
}
