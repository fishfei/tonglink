/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 工业协议网关真机测试：设置 TONGLINK_MODBUS_HOST / TONGLINK_OPCUA_ENDPOINT 后启用。
 *
 * <p>覆盖生产代码路径上的「数据源连通测试 + 读 + 写回读」，使用用户提供的模拟服务端：
 * Modbus 保持寄存器 0~5 = 20,30,40,50,0,0；输入寄存器 0~5 = 200,300,400,500,0,0；
 * OPC UA 点位 ns=2;i=1001（温度）、1002（压力）等。写入测试结束后恢复原值。</p>
 */
@EnabledIfEnvironmentVariable(named = "TONGLINK_MODBUS_HOST", matches = ".+")
class IndustrialGatewayTest {

    private final IndustrialGateway gateway = new IndustrialGateway();

    private String host() {
        return System.getenv("TONGLINK_MODBUS_HOST");
    }

    private String endpoint() {
        return System.getenv().getOrDefault("TONGLINK_OPCUA_ENDPOINT", "");
    }

    private Map<String, Object> modbusFields() {
        return Map.of("host", host(),
                "port", System.getenv().getOrDefault("TONGLINK_MODBUS_PORT", "502"),
                "unitId", System.getenv().getOrDefault("TONGLINK_MODBUS_UNIT", "1"));
    }

    private Map<String, Object> opcuaFields() {
        return Map.of("endpoint", endpoint(), "namespace", "2");
    }

    @AfterEach
    void close() {
        gateway.close();
    }

    @Test
    void Modbus连通测试与寄存器读写() {
        gateway.testModbus(modbusFields());

        Map<String, Object> cfg = Map.of("registerType", "保持寄存器", "startAddress", "0", "count", 6);
        List<Map<String, Object>> held = gateway.readModbus(modbusFields(), cfg);
        assertThat(held).hasSize(6);
        assertThat(held.get(0).get("value")).isEqualTo(20);
        assertThat(held.get(1).get("value")).isEqualTo(30);

        List<Map<String, Object>> input = gateway.readModbus(modbusFields(),
                Map.of("registerType", "输入寄存器", "startAddress", "0", "count", 4));
        assertThat(input).hasSize(4);
        assertThat(input.get(0).get("value")).isEqualTo(200);

        // 写保持寄存器地址 2 为 77，回读确认后恢复原值 40
        gateway.writeModbus(modbusFields(),
                Map.of("registerType", "保持寄存器", "startAddress", "2", "value", "77"));
        List<Map<String, Object>> back = gateway.readModbus(modbusFields(),
                Map.of("registerType", "保持寄存器", "startAddress", "2", "count", 1));
        assertThat(back.get(0).get("value")).isEqualTo(77);
        gateway.writeModbus(modbusFields(),
                Map.of("registerType", "保持寄存器", "startAddress", "2", "value", "40"));
    }

    @Test
    void OpcUa连通测试与点位读写() {
        if (endpoint().isBlank()) {
            return;
        }
        gateway.testOpcua(opcuaFields());

        Map<String, Object> cfg = Map.of("nodeId", "ns=2;i=1001");
        List<Map<String, Object>> read = gateway.readOpcua(opcuaFields(), cfg);
        assertThat(read).hasSize(1);
        Object original = read.get(0).get("value");
        assertThat(original).isNotNull();
        assertThat(read.get(0).get("quality")).isEqualTo("GOOD");

        // 写入 88.5（Double 类型跟随原值），回读后恢复
        gateway.writeOpcua(opcuaFields(), Map.of("nodeId", "ns=2;i=1001", "value", "88.5"));
        List<Map<String, Object>> back = gateway.readOpcua(opcuaFields(), cfg);
        assertThat(((Number) back.get(0).get("value")).doubleValue()).isEqualTo(88.5);
        gateway.writeOpcua(opcuaFields(),
                Map.of("nodeId", "ns=2;i=1001", "value", String.valueOf(original)));
    }
}
