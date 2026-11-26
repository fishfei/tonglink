/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.InputRegister;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.BitVector;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteResponse;
import org.eclipse.milo.opcua.stack.core.types.structured.WriteValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工业协议网关：对上层提供统一的 Modbus TCP / OPC UA 读写能力，屏蔽 j2mod / Milo 细节。
 *
 * <p>数据源连接测试（{@link DataSourceService#probe}）与节点执行器（tonglink-connector）
 * 都通过本网关访问真实设备。连接按端点缓存复用，避免每次读写都重新握手；设备地址变更时
 * 缓存键随之变化，互不影响。</p>
 *
 * <p>连接参数来自数据源的 connectionFields：
 * <ul>
 *   <li>Modbus：host / port / unitId（从站号）</li>
 *   <li>OPC UA：endpoint / namespace，可选 username / password（匿名时留空）</li>
 * </ul>
 */
@Component
public class IndustrialGateway {

    private static final Logger log = LoggerFactory.getLogger(IndustrialGateway.class);

    /** Modbus 主站缓存，键 host:port。 */
    private final Map<String, ModbusTCPMaster> modbusMasters = new ConcurrentHashMap<>();
    /** OPC UA 客户端缓存，键 endpoint。 */
    private final Map<String, OpcUaClient> opcClients = new ConcurrentHashMap<>();

    /** Modbus 连通性测试：建立连接并读取保持寄存器 0，确认从站有响应。 */
    public void testModbus(Map<String, Object> fields) {
        ModbusTCPMaster master = modbus(String.valueOf(fields.getOrDefault("host", "")),
                toInt(fields.get("port"), 502));
        try {
            master.readMultipleRegisters(unitId(fields), 0, 1);
        } catch (Exception e) {
            throw new IllegalStateException("Modbus 从站无响应：" + e.getMessage(), e);
        }
    }

    /** OPC UA 连通性测试：匿名建立会话并读取服务端状态节点。 */
    public void testOpcua(Map<String, Object> fields) {
        OpcUaClient client = opcClient(String.valueOf(fields.getOrDefault("endpoint", "")));
        try {
            DataValue dv = client.readValue(0, TimestampsToReturn.Both,
                    NodeId.parse("ns=0;i=2256")).get();
            if (dv == null || dv.getStatusCode() == null || dv.getStatusCode().isBad()) {
                throw new IllegalStateException("服务端状态节点读取失败");
            }
        } catch (Exception e) {
            throw new IllegalStateException("OPC UA 服务端无响应：" + e.getMessage(), e);
        }
    }

    /**
     * Modbus 读取。registerType 支持 holding/input/coil/discrete，startAddress 可用 0-based
     * 或传统 4xxxx/0xxxx/1xxxx/3xxxx 地址（自动减去偏移量）。
     */
    public List<Map<String, Object>> readModbus(Map<String, Object> fields, Map<String, Object> cfg) {
        ModbusTCPMaster master = modbus(String.valueOf(fields.getOrDefault("host", "")),
                toInt(fields.get("port"), 502));
        int unit = unitId(fields);
        String type = String.valueOf(cfg.getOrDefault("registerType", "holding")).toLowerCase();
        int start = address(cfg, type);
        int count = Math.max(1, Math.min(125, toInt(cfg.get("count"), 1)));
        String ts = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            switch (type) {
                case "holding", "hold", "holdingregister", "保持寄存器" -> {
                    Register[] regs = master.readMultipleRegisters(unit, start, count);
                    for (int i = 0; i < regs.length; i++) {
                        rows.add(point(type, start + i, regs[i].getValue(), "GOOD", ts));
                    }
                }
                case "input", "inputregister", "输入寄存器" -> {
                    InputRegister[] regs = master.readInputRegisters(unit, start, count);
                    for (int i = 0; i < regs.length; i++) {
                        rows.add(point(type, start + i, regs[i].getValue(), "GOOD", ts));
                    }
                }
                case "coil", "coils", "线圈" -> {
                    BitVector bv = master.readCoils(unit, start, count);
                    for (int i = 0; i < bv.size(); i++) {
                        rows.add(point(type, start + i, bv.getBit(i), "GOOD", ts));
                    }
                }
                case "discrete", "discreteinput", "离散输入" -> {
                    BitVector bv = master.readInputDiscretes(unit, start, count);
                    for (int i = 0; i < bv.size(); i++) {
                        rows.add(point(type, start + i, bv.getBit(i), "GOOD", ts));
                    }
                }
                default -> throw new IllegalArgumentException("未知的寄存器类型：" + type);
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Modbus 读取失败：" + e.getMessage(), e);
        }
    }

    /** Modbus 写入：holding 用功能码 06，coil 用功能码 05；支持单值或多值（逗号分隔）。 */
    public List<Map<String, Object>> writeModbus(Map<String, Object> fields, Map<String, Object> cfg) {
        ModbusTCPMaster master = modbus(String.valueOf(fields.getOrDefault("host", "")),
                toInt(fields.get("port"), 502));
        int unit = unitId(fields);
        String type = String.valueOf(cfg.getOrDefault("registerType", "holding")).toLowerCase();
        int start = address(cfg, type);
        String raw = String.valueOf(cfg.getOrDefault("value", ""));
        String[] values = raw.split(",");
        String ts = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (int i = 0; i < values.length; i++) {
                String v = values[i].trim();
                int addr = start + i;
                if (type.startsWith("coil") || "线圈".equals(type)) {
                    boolean on = "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
                    master.writeCoil(unit, addr, on);
                    rows.add(point(type, addr, on, "GOOD", ts));
                } else {
                    master.writeSingleRegister(unit, addr, new SimpleRegister(Integer.parseInt(v)));
                    rows.add(point(type, addr, Integer.parseInt(v), "GOOD", ts));
                }
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("Modbus 写入失败：" + e.getMessage(), e);
        }
    }

    /** OPC UA 读取：nodeId 支持字符串（ns=2;i=1001）与纯数字简写（1001 → ns=2;i=1001）。 */
    public List<Map<String, Object>> readOpcua(Map<String, Object> fields, Map<String, Object> cfg) {
        OpcUaClient client = opcClient(String.valueOf(fields.getOrDefault("endpoint", "")));
        String[] nodeIds = nodeIds(cfg, fields);
        String ts = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (String nodeId : nodeIds) {
                DataValue dv = client.readValue(0, TimestampsToReturn.Both, NodeId.parse(nodeId)).get();
                Object value = dv.getValue() == null ? null : dv.getValue().getValue();
                String quality = dv.getStatusCode() == null ? "UNKNOWN"
                        : (dv.getStatusCode().isGood() ? "GOOD" : "BAD");
                rows.add(point(nodeId, value, quality, ts));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("OPC UA 读取失败：" + e.getMessage(), e);
        }
    }

    /** OPC UA 写入：按 nodeIds 顺序写入 value（逗号分隔），类型跟随节点现有值类型。 */
    public List<Map<String, Object>> writeOpcua(Map<String, Object> fields, Map<String, Object> cfg) {
        OpcUaClient client = opcClient(String.valueOf(fields.getOrDefault("endpoint", "")));
        String[] nodeIds = nodeIds(cfg, fields);
        String[] values = String.valueOf(cfg.getOrDefault("value", "")).split(",");
        String ts = now();
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (int i = 0; i < nodeIds.length; i++) {
                NodeId nodeId = NodeId.parse(nodeIds[i]);
                DataValue current = client.readValue(0, TimestampsToReturn.Both, nodeId).get();
                Object typed = convert(values[i].trim(),
                        current.getValue() == null ? null : current.getValue().getValue());
                WriteResponse wr = client.write(List.of(new WriteValue(nodeId,
                        AttributeId.Value.uid(), null, new DataValue(new Variant(typed))))).get();
                String quality = wr.getResults()[0].isGood() ? "GOOD" : "BAD";
                rows.add(point(nodeIds[i], typed, quality, ts));
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("OPC UA 写入失败：" + e.getMessage(), e);
        }
    }

    /** 按现有值类型转换写入文本，保证 Double/Int/String/Boolean 节点写入类型正确。 */
    private Object convert(String text, Object sample) {
        if (sample instanceof Number) {
            String s = sample.toString();
            return s.contains(".") ? Double.parseDouble(text) : Long.parseLong(text);
        }
        if (sample instanceof Boolean) {
            return "1".equals(text) || "true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text);
        }
        return text;
    }

    private Map<String, Object> point(String type, int addr, Object value, String quality, String ts) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("registerType", type);
        row.put("address", addr);
        row.put("value", value);
        row.put("quality", quality);
        row.put("ts", ts);
        return row;
    }

    private Map<String, Object> point(String nodeId, Object value, String quality, String ts) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("nodeId", nodeId);
        row.put("value", value);
        row.put("quality", quality);
        row.put("ts", ts);
        return row;
    }

    /** 传统地址（40001/30001/00001/10001）转成 0-based 协议地址。 */
    private int address(Map<String, Object> cfg, String type) {
        int raw = toInt(cfg.get("startAddress"), 0);
        if (raw < 40000) {
            return raw;
        }
        if (type.startsWith("discrete") || "离散输入".equals(type)) return raw - 10001;
        if (type.startsWith("input") || "输入寄存器".equals(type)) return raw - 30001;
        if (type.startsWith("coil") || "线圈".equals(type)) return raw - 1;
        return raw - 40001; // holding
    }

    private String[] nodeIds(Map<String, Object> cfg, Map<String, Object> fields) {
        String raw = String.valueOf(cfg.getOrDefault("nodeId", fields.getOrDefault("nodeId", "")));
        if (raw.isBlank()) {
            throw new IllegalArgumentException("未配置 nodeId");
        }
        int ns = toInt(fields.get("namespace"), 2);
        String[] parts = raw.split(",");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (p.matches("\\d+")) {
                parts[i] = "ns=" + ns + ";i=" + p;
            }
        }
        return parts;
    }

    private int unitId(Map<String, Object> fields) {
        return toInt(fields.get("unitId"), 1);
    }

    private synchronized ModbusTCPMaster modbus(String host, int port) {
        if (host.isBlank()) {
            throw new IllegalArgumentException("未配置 Modbus 主机地址");
        }
        String key = host + ":" + port;
        ModbusTCPMaster master = modbusMasters.get(key);
        if (master != null && master.isConnected()) {
            return master;
        }
        master = new ModbusTCPMaster(host, port, 3000, false);
        try {
            master.connect();
        } catch (Exception e) {
            throw new IllegalStateException("无法连接 Modbus 从站 " + key + "：" + e.getMessage(), e);
        }
        modbusMasters.put(key, master);
        return master;
    }

    private OpcUaClient opcClient(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("未配置 OPC UA Endpoint");
        }
        OpcUaClient cached = opcClients.get(endpoint);
        if (cached != null) {
            return cached;
        }
        try {
            OpcUaClient client = OpcUaClient.create(endpoint);
            client.connect().get();
            // 仅保留一个实例，避免并发下重复建连
            OpcUaClient existing = opcClients.putIfAbsent(endpoint, client);
            if (existing != null) {
                client.disconnect().get();
                return existing;
            }
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("无法连接 OPC UA 服务端 " + endpoint + "：" + e.getMessage(), e);
        }
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    @PreDestroy
    public void close() {
        for (ModbusTCPMaster master : modbusMasters.values()) {
            try {
                master.disconnect();
            } catch (Exception e) {
                log.debug("关闭 Modbus 连接失败：{}", e.getMessage());
            }
        }
        modbusMasters.clear();
        for (OpcUaClient client : opcClients.values()) {
            try {
                client.disconnect().get();
            } catch (Exception e) {
                log.debug("关闭 OPC UA 会话失败：{}", e.getMessage());
            }
        }
        opcClients.clear();
    }
}
