/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.app;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.DataSource;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionLog;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.Project;
import cn.tonglink.model.entity.UserAccount;
import cn.tonglink.model.entity.Variable;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.repository.ExecutionRepository;
import cn.tonglink.repository.FlowRepository;
import cn.tonglink.repository.MetaRepository;
import cn.tonglink.repository.UserRepository;
import cn.tonglink.security.CredentialTypeRegistry;
import cn.tonglink.security.CryptoService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 首次启动写入演示数据。
 *
 * <p>目标：用户第一次打开页面就能看到完整形态——3 个项目、13 个数据源（含认证配置）、
 * 3 条示例流程，以及几条历史执行记录。没有这些数据，看板、执行日志、AI 诊断
 * 全是空页面，很难判断系统是否正常工作。</p>
 *
 * <p>幂等：仅在用户表为空时执行，不会覆盖用户后续的修改。删除 {@code data/tonglink.db}
 * 即可恢复出厂状态。</p>
 *
 * <p>唯一内置账号：admin / Admin@123（与前端登录页的「一键填充」保持一致）。</p>
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository users;
    private final MetaRepository meta;
    private final FlowRepository flows;
    private final ExecutionRepository executions;
    private final PasswordEncoder encoder;
    private final CryptoService crypto;
    private final CredentialTypeRegistry credentialTypes;

    /** 是否重建演示账号；默认关闭，避免生产环境覆盖用户修改后的口令。 */
    @Value("${tonglink.auth.reset-demo-users:false}")
    private boolean resetDemoUsers;

    /** 内置测试用的 MySQL 连接，可用环境变量覆盖，便于部署到别的测试库。 */
    @Value("${tonglink.test.mysql.jdbc-url:}")
    private String testMysqlJdbcUrl;
    @Value("${tonglink.test.mysql.username:}")
    private String testMysqlUsername;
    @Value("${tonglink.test.mysql.password:}")
    private String testMysqlPassword;

    public DataInitializer(UserRepository users, MetaRepository meta, FlowRepository flows,
                           ExecutionRepository executions, PasswordEncoder encoder, CryptoService crypto,
                           CredentialTypeRegistry credentialTypes) {
        this.users = users;
        this.meta = meta;
        this.flows = flows;
        this.executions = executions;
        this.encoder = encoder;
        this.crypto = crypto;
        this.credentialTypes = credentialTypes;
    }

    @Override
    public void run(String... args) {
        // 系统只保留 admin 一个账户：历史库里的演示角色每次启动都清理，避免残留可登录入口
        users.deleteByUsername("engineer");
        users.deleteByUsername("viewer");
        // 测试项目独立于演示账号初始化：老库升级后也能自动补上，且不会覆盖用户已有修改
        seedTestProject();
        if (users.count() > 0) {
            return;
        }
        seedUsers();
        seedMeta();
        seedFlows();
        seedExecutions();
    }

    // ---------------- 内置「测试」项目 ----------------

    private static final String TEST_PROJECT = "P-TEST";

    /** 外部天气接口不可达时的兜底响应，输出里显式标 simulated。 */
    private static final String WEATHER_FALLBACK = """
            {"simulated":true,"reason":"外部天气接口不可达","daily":{"time":[],"weather_code":[],"temperature_2m_max":[],"temperature_2m_min":[],"precipitation_probability_max":[]}}
            """;

    /**
     * 内置测试项目与 4 条端到端数据流，幂等写入。
     *
     * <p>与演示数据不同，这一段每次启动都会检查：即使数据库里已有用户，升级后仍会自动补齐
     * 测试项目；已存在的流程/数据源/凭据不会被覆盖，用户填好的地址和认证信息得以保留。</p>
     */
    private void seedTestProject() {
        String now = meta.now();
        if (meta.project(TEST_PROJECT) == null) {
            meta.saveProject(new Project(TEST_PROJECT, "测试", "TEST", "#0EA5E9",
                    "内置测试项目：天气入库、飞书协同与工业协议模拟场景", now, now));
        }

        // 每个数据源都由类型定义，连接与认证参数在同一组字段里；节点专用参数放 config
        ensureTypedSource("DS-TEST-FS", "飞书多维表格（测试·待配置）", "feishu", "unconfigured",
                Map.of("appId", "请填写 appId", "appSecret", "请填写 appSecret"),
                Map.of("appToken", "bascnREPLACE_ME", "tableId", "tblREPLACE_ME"), now);
        String jdbc = testMysqlJdbcUrl == null || testMysqlJdbcUrl.isBlank()
                ? "jdbc:mysql://127.0.0.1:3306/weather?useSSL=false&serverTimezone=Asia/Shanghai"
                : testMysqlJdbcUrl;
        ensureTypedSource("DS-TEST-MYSQL", "MySQL 天气库（内置测试库）", "database", "connected",
                Map.of("driver", "MySQL", "host", "127.0.0.1", "port", "3306", "database", "weather",
                        "username", testMysqlUsername == null ? "" : testMysqlUsername,
                        "password", testMysqlPassword == null ? "" : testMysqlPassword,
                        "jdbcUrl", jdbc),
                Map.of("table", "weather_forecast"), now);
        ensureTypedSource("DS-TEST-MODBUS", "Modbus TCP（测试·模拟）", "modbus", "unconfigured",
                Map.of("host", "127.0.0.1", "port", "502", "unitId", "1"), Map.of(), now);
        ensureTypedSource("DS-TEST-OPCUA", "OPC UA（测试·模拟）", "opcua", "unconfigured",
                Map.of("endpoint", "opc.tcp://127.0.0.1:4840", "namespace", "3"), Map.of(), now);
        ensureTypedSource("DS-TEST-FS-CRED", "飞书应用认证（测试·待配置）", "feishu", "unconfigured",
                Map.of("appId", "请填写 appId", "appSecret", "请填写 appSecret"), Map.of(), now);

        String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=22.5431&longitude=114.0579"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=Asia%2FShanghai&forecast_days=3";
        // 不使用可选链 ?. ：即使用户环境里只有老版本 Node 也能执行
        String parseWeather = """
                const raw = $json.body || '{}';
                const data = typeof raw === 'string' ? JSON.parse(raw) : raw;
                const daily = data.daily || {};
                const labels = {0:'晴',1:'多云',2:'阴',3:'阴',45:'雾',48:'雾',51:'小雨',61:'中雨',80:'阵雨'};
                const sim = data.simulated === true;
                const dateOf = n => { const d = new Date(); d.setDate(d.getDate() + n);
                  return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0'); };
                const times = sim ? [0,1,2].map(dateOf) : (daily.time || []);
                const codes = sim ? [0,1,2] : (daily.weather_code || []);
                const tmax = sim ? [30,31,29] : (daily.temperature_2m_max || []);
                const tmin = sim ? [24,25,23] : (daily.temperature_2m_min || []);
                const pop = sim ? [30,40,20] : (daily.precipitation_probability_max || []);
                return times.map((day, i) => ({
                  city: '深圳',
                  forecast_date: day,
                  weather: labels[codes[i]] || ('代码' + codes[i]),
                  temp_max: tmax[i],
                  temp_min: tmin[i],
                  precipitation_probability: pop[i],
                  source: sim ? 'fixture' : 'open-meteo'
                }));
                """;

        // a. 深圳天气 → 飞书多维表格
        FlowGraph feishu = new FlowGraph(1, "FLOW-TEST-WEATHER-FS", "深圳 3 天天气 → 飞书多维表格", TEST_PROJECT, Map.of(),
                List.of(
                        node("w1", "trigger", "schedule", "每天 08:30 触发",
                                Map.of("mode", "cron", "cron", "0 30 8 * * ?", "timezone", "Asia/Shanghai", "enabled", "是"), 60, 200),
                        node("w2", "source", "http", "Open-Meteo 深圳天气",
                                Map.of("method", "GET", "url", weatherUrl, "timeout", "10000ms",
                                        "fallbackBody", WEATHER_FALLBACK), 320, 200),
                        node("w3", "code", "nodejs", "解析未来 3 天天气",
                                Map.of("code", parseWeather, "inputMode", "整批执行"), 600, 200),
                        node("w4", "output", "feishu_bitable", "写入飞书多维表格",
                                Map.of("credential", "DS-TEST-FS-CRED", "appToken", "bascnREPLACE_ME",
                                        "tableId", "tblREPLACE_ME", "operation", "新增记录",
                                        "fieldMapping", "{\"日期\":\"forecast_date\",\"城市\":\"city\",\"天气\":\"weather\","
                                                + "\"最高温\":\"temp_max\",\"最低温\":\"temp_min\",\"降水概率\":\"precipitation_probability\"}",
                                        "batchSize", 100), 880, 200)),
                List.of(edge("we1", "w1", "w2"), edge("we2", "w2", "w3"), edge("we3", "w3", "w4")));
        testFlow("FLOW-TEST-WEATHER-FS", "深圳 3 天天气 → 飞书多维表格",
                "Open-Meteo 免费天气接口 → 结构化 3 天预报 → 飞书多维表格；填好凭据后自动真实写入", feishu, now);

        // b. 深圳天气 → MySQL
        FlowGraph mysql = new FlowGraph(1, "FLOW-TEST-WEATHER-MYSQL", "深圳 3 天天气 → MySQL", TEST_PROJECT, Map.of(),
                List.of(
                        node("m1", "trigger", "schedule", "每天 08:30 触发",
                                Map.of("mode", "cron", "cron", "0 30 8 * * ?", "timezone", "Asia/Shanghai", "enabled", "是"), 60, 200),
                        node("m2", "source", "http", "Open-Meteo 深圳天气",
                                Map.of("method", "GET", "url", weatherUrl, "timeout", "10000ms",
                                        "fallbackBody", WEATHER_FALLBACK), 320, 200),
                        node("m3", "code", "nodejs", "解析未来 3 天天气",
                                Map.of("code", parseWeather, "inputMode", "整批执行"), 600, 200),
                        node("m4", "source", "mysql", "批量写入天气表",
                                Map.of("connection", "DS-TEST-MYSQL", "operation", "insert",
                                        "table", "weather_forecast",
                                        "columns", "city,forecast_date,weather,temp_max,temp_min,precipitation_probability,source",
                                        "timeout", "30000ms",
                                        "simulateOnError", "false"), 880, 200)),
                List.of(edge("me1", "m1", "m2"), edge("me2", "m2", "m3"), edge("me3", "m3", "m4")));
        testFlow("FLOW-TEST-WEATHER-MYSQL", "深圳 3 天天气 → MySQL",
                "同一份 3 天预报批量 INSERT 到 weather_forecast；未配置数据源时降级为模拟写入", mysql, now);

        // c. Modbus 场景：Python 模拟从站寄存器，再解析并触发阈值告警
        String modbusSlave = """
                import random, struct, time
                regs = [int(random.uniform(600, 950)) for _ in range(4)]
                frame = struct.pack('>4H', *regs)
                print('模拟读取保持寄存器 40001-40004:', frame.hex())
                result = [{
                    'ts': time.strftime('%Y-%m-%dT%H:%M:%S'),
                    'slaveId': 1,
                    'startAddress': '40001',
                    'rawHex': frame.hex(),
                    'registers': regs,
                    'temperature': regs[0] / 10.0,
                    'humidity': regs[1] / 10.0
                }]
                """;
        String modbusParse = """
                rows = []
                for item in _items:
                    temp = float(item.get('temperature', 0))
                    alarm = temp > 85.0
                    rows.append(dict(item, alarm=alarm, level='HIGH' if alarm else 'NORMAL'))
                    print('温度', temp, '℃ ->', '超限告警' if alarm else '正常')
                result = rows
                """;
        FlowGraph modbus = new FlowGraph(1, "FLOW-TEST-MODBUS", "Modbus 寄存器采集与阈值告警（模拟）", TEST_PROJECT, Map.of(),
                List.of(
                        node("b1", "trigger", "schedule", "每分钟轮询",
                                Map.of("mode", "minutes", "everyMinutes", 1, "enabled", "是"), 60, 200),
                        node("b2", "code", "python", "模拟 Modbus TCP 从站",
                                Map.of("code", modbusSlave, "inputMode", "整批执行"), 320, 200),
                        node("b3", "code", "python", "解析寄存器并判阈值",
                                Map.of("code", modbusParse, "inputMode", "整批执行"), 600, 200),
                        node("b4", "output", "alert", "温度超限告警",
                                Map.of("channel", "企业微信", "webhook", "https://qyapi.weixin.qq.com/请填写",
                                        "titleTemplate", "【Modbus】温度 {{ $json.temperature }}℃ 超限",
                                        "messageTemplate", "从站 {{ $json.slaveId }}，地址 {{ $json.startAddress }}，当前 {{ $json.temperature }}℃",
                                        "severity", "警告", "suppressMinutes", 10), 880, 200)),
                List.of(edge("be1", "b1", "b2"), edge("be2", "b2", "b3"), edge("be3", "b3", "b4")));
        testFlow("FLOW-TEST-MODBUS", "Modbus 寄存器采集与阈值告警（模拟）",
                "Python 生成 Modbus 保持寄存器帧 → 解析温度/湿度 → 超 85℃ 触发告警，可替换为真实 Modbus 节点", modbus, now);

        // d. OPC UA 场景：Python 模拟订阅服务端点位，再过滤质量戳与检测异常
        String opcuaServer = """
                import random, time
                points = [
                    {'nodeId': 'ns=3;s=Temperature.PT101', 'value': round(random.uniform(60, 95), 2), 'quality': 'GOOD'},
                    {'nodeId': 'ns=3;s=Pressure.PT201', 'value': round(random.uniform(0.3, 0.9), 3), 'quality': 'GOOD'},
                    {'nodeId': 'ns=3;s=Vibration.VT301', 'value': round(random.uniform(0.1, 2.5), 3), 'quality': 'GOOD'}
                ]
                print('模拟 OPC UA 订阅推送', len(points), '个点位')
                result = [dict(ts=time.strftime('%Y-%m-%dT%H:%M:%S'), **p) for p in points]
                """;
        String opcuaCheck = """
                rows = []
                for item in _items:
                    if item.get('quality') != 'GOOD':
                        continue
                    node_id = item.get('nodeId', '')
                    value = float(item.get('value', 0))
                    limit = 85.0 if 'Temperature' in node_id else (2.0 if 'Vibration' in node_id else 1.0)
                    abnormal = value > limit
                    rows.append(dict(item, limit=limit, abnormal=abnormal))
                    print(node_id, value, '超限' if abnormal else '正常')
                result = rows
                """;
        FlowGraph opcua = new FlowGraph(1, "FLOW-TEST-OPCUA", "OPC UA 点位订阅与异常检测（模拟）", TEST_PROJECT, Map.of(),
                List.of(
                        node("o1", "trigger", "schedule", "每分钟订阅",
                                Map.of("mode", "minutes", "everyMinutes", 1, "enabled", "是"), 60, 200),
                        node("o2", "code", "python", "模拟 OPC UA 服务端点位",
                                Map.of("code", opcuaServer, "inputMode", "整批执行"), 320, 200),
                        node("o3", "code", "python", "质量过滤与异常检测",
                                Map.of("code", opcuaCheck, "inputMode", "整批执行"), 600, 200),
                        node("o4", "output", "alert", "点位异常通知",
                                Map.of("channel", "邮件", "webhook", "ops@example.local",
                                        "titleTemplate", "【OPC UA】点位 {{ $json.nodeId }} 异常",
                                        "messageTemplate", "点位 {{ $json.nodeId }} 当前 {{ $json.value }}，阈值 {{ $json.limit }}",
                                        "severity", "警告", "suppressMinutes", 10), 880, 200)),
                List.of(edge("oe1", "o1", "o2"), edge("oe2", "o2", "o3"), edge("oe3", "o3", "o4")));
        testFlow("FLOW-TEST-OPCUA", "OPC UA 点位订阅与异常检测（模拟）",
                "Python 模拟 OPC UA 服务端 3 个点位 → 质量戳过滤与阈值检测 → 异常通知", opcua, now);
    }

    /** 幂等写入内置测试数据；类型决定类别与协议，密码字段加密保存。 */
    private void ensureTypedSource(String id, String name, String type, String status,
                                   Map<String, Object> fields, Map<String, Object> config, String now) {
        if (meta.source(id) != null) return;
        meta.saveSource(typedSource(id, name, type, status, fields, config, now));
    }

    /** 构造类型化数据源：类别/协议由类型目录推导，密码类字段统一 AES-GCM 加密。 */
    private DataSource typedSource(String id, String name, String type, String status,
                                   Map<String, Object> fields, Map<String, Object> config, String now) {
        Map<String, Object> stored = new LinkedHashMap<>(fields);
        for (String secret : credentialTypes.secretFields(type)) {
            Object value = stored.get(secret);
            if (value != null && !String.valueOf(value).isBlank()
                    && !String.valueOf(value).startsWith("v1:")) {
                stored.put(secret, crypto.encrypt(String.valueOf(value)));
            }
        }
        return new DataSource(id, name, credentialTypes.category(type),
                credentialTypes.protocol(type, fields), JsonUtil.stringify(config), type,
                JsonUtil.stringify(stored), status, now, now, now);
    }

    private void testFlow(String id, String name, String description, FlowGraph graph, String now) {
        Flow existing = flows.flow(id);
        if (existing == null) {
            String json = JsonUtil.stringify(graph);
            flows.saveFlow(new Flow(id, TEST_PROJECT, name, description, "paused",
                    json, json, 0, "—", "—", 0L, "—", now, now));
            return;
        }
        repairTestFlow(existing, graph);
    }

    /**
     * 修复早期版本写入的测试流程：
     * 1) 把天气解析脚本里的可选链 ?. 换成兼容写法；
     * 2) MySQL 写入节点开启 simulateOnError=false，连接失败时如实报错而不是伪装成功。
     * 只改这两个已知问题，用户在画布上的其他调整会保留。
     */
    private void repairTestFlow(Flow existing, FlowGraph fresh) {
        FlowGraph current;
        try {
            current = JsonUtil.parse(existing.graphJson(), FlowGraph.class);
        } catch (Exception e) {
            return;
        }
        if (current == null || current.nodes() == null) return;
        List<GraphNode> patched = new ArrayList<>();
        boolean changed = false;
        for (GraphNode node : current.nodes()) {
            GraphNode fixed = fresh.nodes().stream()
                    .filter(n -> n.id().equals(node.id())).findFirst().orElse(null);
            if (fixed == null) {
                patched.add(node);
                continue;
            }
            Map<String, Object> config = new LinkedHashMap<>(
                    node.config() == null ? Map.of() : node.config());
            String code = String.valueOf(config.getOrDefault("code", ""));
            if (List.of("w3", "m3").contains(node.id())
                    && (code.contains("?.") || !code.contains("data.simulated"))) {
                config.put("code", fixed.config().get("code"));
                changed = true;
            }
            if (List.of("w2", "m2").contains(node.id())
                    && String.valueOf(config.getOrDefault("fallbackBody", "")).isBlank()) {
                config.put("fallbackBody", WEATHER_FALLBACK);
                changed = true;
            }
            if ("m4".equals(node.id())) {
                if (!"false".equals(String.valueOf(config.get("simulateOnError")))) {
                    config.put("simulateOnError", "false");
                    changed = true;
                }
                String columns = String.valueOf(config.getOrDefault("columns", ""));
                if (!columns.contains("source")) {
                    config.put("columns", columns + ",source");
                    changed = true;
                }
            }
            patched.add(new GraphNode(node.id(), node.type(), node.subtype(), node.nodeVersion(),
                    node.title(), node.position(), config));
        }
        if (!changed) return;
        FlowGraph updated = new FlowGraph(current.schemaVersion(), current.id(), current.name(),
                current.projectId(), current.settings(), patched, current.connections());
        String json = JsonUtil.stringify(updated);
        // 发布快照保持不动；测试流默认未发布，手动运行使用草稿图
        String published = existing.publishedGraphJson();
        flows.updateFlow(new Flow(existing.id(), existing.projectId(), existing.name(),
                existing.description(), existing.status(), json, published, existing.publishedVersion(),
                existing.nextRun(), existing.lastRun(), existing.records(), existing.successRate(),
                existing.createdAt(), meta.now()));
    }

    /** 唯一内置账号：admin，密码与前端登录页的「一键填充」保持一致。 */
    private void seedUsers() {
        // 仅在显式开启时重建内置账号（启动参数 --tonglink.auth.reset-demo-users=true）。
        // 默认关闭，生产环境用户自行设置的口令不会被覆盖。
        if (resetDemoUsers) {
            users.clear();
        }
        users.insert(new UserAccount(users.newId(), "admin",
                encoder.encode("Admin@123"), "ADMIN", "系统管理员", true));
    }

    /** 项目、数据源、凭据、变量。 */
    private void seedMeta() {
        String now = meta.now();
        meta.saveProject(new Project("P-001", "车间A · 设备监控", "WORKSHOP-A", "#2563EB",
                "温度、压力、设备状态采集", now, now));
        meta.saveProject(new Project("P-002", "能源管理", "ENERGY", "#059669",
                "能耗采集与分析", now, now));
        meta.saveProject(new Project("P-003", "质量与协同", "QUALITY", "#7C3AED",
                "质量数据与飞书协同", now, now));

        typedSource("DS-001", "OPC UA-反应釜温度", "opcua", "connected",
                Map.of("endpoint", "opc.tcp://10.20.3.11:4840", "namespace", "3"), Map.of(), now);
        typedSource("DS-002", "Modbus-产线PLC", "modbus", "connected",
                Map.of("host", "10.20.3.20", "port", "502", "unitId", "1"), Map.of(), now);
        typedSource("DS-003", "MQTT Broker", "mqtt", "connected",
                Map.of("uri", "tcp://broker.factory.local:1883"), Map.of(), now);
        typedSource("DS-004", "MES HTTP 接口", "http", "connected",
                Map.of("baseUrl", "https://mes.factory.local/api/v1"), Map.of(), now);
        typedSource("DS-005", "MySQL-MES", "database", "connected",
                Map.of("driver", "MySQL", "host", "10.20.1.30", "port", "3306", "database", "mes"),
                Map.of(), now);
        typedSource("DS-006", "PostgreSQL-时序", "database", "connected",
                Map.of("driver", "PostgreSQL", "host", "10.20.1.40", "port", "5432", "database", "telemetry"),
                Map.of(), now);
        typedSource("DS-007", "Kafka-factory.events", "kafka", "connected",
                Map.of("bootstrapServers", "10.20.1.40:9092"), Map.of("topic", "factory.events"), now);
        typedSource("DS-008", "飞书应用", "feishu", "connected",
                Map.of("appId", "cli_demo_app", "appSecret", "cli_demo_app_secret"), Map.of(), now);

        typedSource("DS-101", "MES API Token", "api_key", "connected",
                Map.of("apiKey", "mes-token-demo-7f3a91",
                        "baseUrl", "https://mes.factory.local/api/v1"), Map.of(), now);
        typedSource("DS-102", "MQTT 认证", "mqtt", "connected",
                Map.of("uri", "tcp://127.0.0.1:1883", "username", "demo", "password", "mqtt-demo-pass"),
                Map.of(), now);
        typedSource("DS-103", "OPC UA 证书", "api_key", "connected",
                Map.of("apiKey", "opcua-cert-demo"), Map.of(), now);
        typedSource("DS-104", "测试 Webhook Key", "api_key", "connected",
                Map.of("apiKey", "test-wh-7f3a91"), Map.of(), now);
        typedSource("DS-105", "飞书应用（认证）", "feishu", "connected",
                Map.of("appId", "cli_demo_app", "appSecret", "cli_demo_app_secret"), Map.of(), now);

        String now2 = meta.now();
        meta.saveVariable(new Variable("V-001", "温度阈值", "TEMP_THRESHOLD", "prod",
                "85", "global", "高温告警阈值", now2, now2));
        meta.saveVariable(new Variable("V-002", "产线名称", "LINE_NAME", "prod",
                "A线", "P-001", "仅车间A项目可见", now2, now2));
    }

    /** 三条示例流程，覆盖「采集入库」「定时计算推送」「业务系统同步」三种形态。 */
    private void seedFlows() {
        String now = meta.now();

        FlowGraph temp = new FlowGraph(1, "FLOW-TEMP", "车间温度实时采集", "P-001", Map.of(),
                List.of(
                        node("n11", "trigger", "schedule", "定时触发",
                                Map.of("mode", "minutes", "everyMinutes", 5, "timezone", "Asia/Shanghai"), 60, 170),
                        node("n12", "source", "opcua", "OPC UA 读取",
                                Map.of("datasource", "DS-001", "nodeId", "ns=3;s=Temperature.PT101"), 350, 170),
                        node("n13", "code", "nodejs", "数据清洗",
                                Map.of("code", "return $input.map(x => ({...x, value: Number(x.value)}));"), 650, 170),
                        node("n14", "output", "timeseries", "写入时序库",
                                Map.of("connection", "DS-006", "table", "plant_a.telemetry"), 950, 170)),
                List.of(edge("e11", "n11", "n12"), edge("e12", "n12", "n13"), edge("e13", "n13", "n14")));
        saveFlow("FLOW-TEMP", "P-001", "车间温度实时采集",
                "每 5 分钟读取 OPC UA 温度点位，清洗后写入时序库", temp, now, 48210L, "99.2%");

        FlowGraph oee = new FlowGraph(1, "FLOW-OEE", "产线OEE日报", "P-001", Map.of(),
                List.of(
                        node("n21", "trigger", "schedule", "定时触发",
                                Map.of("mode", "cron", "cron", "0 0 8 * * ?"), 60, 200),
                        node("n22", "source", "modbus", "Modbus 读取",
                                Map.of("datasource", "DS-002", "startAddress", "40001", "count", 10), 350, 200),
                        node("n23", "code", "python", "计算 OEE",
                                Map.of("code", "result = [{'oee': 0.86, 'source': 'modbus'}]"), 650, 200),
                        node("n24", "output", "http", "HTTP 推送",
                                Map.of("method", "POST", "url", "https://mes.factory.local/api/v1/oee",
                                        "auth", "DS-101"), 950, 200)),
                List.of(edge("e21", "n21", "n22"), edge("e22", "n22", "n23"), edge("e23", "n23", "n24")));
        saveFlow("FLOW-OEE", "P-001", "产线OEE日报",
                "每天 08:00 读取 Modbus 寄存器并推送 MES", oee, now, 1152L, "96.8%");

        FlowGraph feishu = new FlowGraph(1, "FLOW-FS", "设备巡检记录同步飞书", "P-003", Map.of(),
                List.of(
                        node("n51", "trigger", "schedule", "定时触发",
                                Map.of("mode", "minutes", "everyMinutes", 60), 60, 200),
                        node("n52", "source", "mysql", "MySQL 查询",
                                Map.of("connection", "DS-005", "operation", "select",
                                        "table", "plant.inspection"), 350, 200),
                        node("n53", "output", "feishu_bitable", "写入飞书",
                                Map.of("credential", "DS-105", "appToken", "bascnDemo",
                                        "tableId", "tblDemo", "operation", "新增记录"), 650, 200)),
                List.of(edge("e51", "n51", "n52"), edge("e52", "n52", "n53")));
        saveFlow("FLOW-FS", "P-003", "设备巡检记录同步飞书",
                "每小时同步巡检记录到飞书多维表格", feishu, now, 0L, "—");
    }

    /**
     * 预置执行记录。
     *
     * <p>让执行日志页、看板趋势与 AI 诊断在首次打开时就有可观察的数据，
     * 其中一条故意留下 Python 类型转换失败，用于演示「AI 失败诊断」。</p>
     */
    private void seedExecutions() {
        LocalDateTime now = LocalDateTime.now().withNano(0);

        // 成功的采集执行
        String okId = "EXEC-SEED-0001";
        String okStart = now.minusMinutes(3).toString();
        executions.saveExecution(new Execution(okId, "FLOW-TEMP", 1, "schedule", "SUCCESS",
                okStart, now.minusMinutes(3).plusSeconds(1).toString(), 312L, null, null));
        seedNode(okId, "n11", "trigger", "SUCCESS", 12, "[]", "[{\"mode\":\"minutes\"}]", null);
        seedNode(okId, "n12", "source", "SUCCESS", 640,
                "[]", "[{\"tag\":\"PT101\",\"value\":82.36,\"quality\":\"GOOD\"}]", null);
        seedNode(okId, "n13", "code", "SUCCESS", 110,
                "[{\"tag\":\"PT101\",\"value\":82.36}]", "[{\"tag\":\"PT101\",\"value\":82.36}]", null);
        seedNode(okId, "n14", "output", "SUCCESS", 440, "[]",
                "[{\"simulated\":true,\"affected\":1}]", null);
        seedLog(okId, null, "INFO", "执行已入队，流程：车间温度实时采集", okStart);
        seedLog(okId, "n12", "INFO", "节点完成：OPC UA 读取，输出 1 条，耗时 640ms",
                now.minusMinutes(3).toString());
        seedLog(okId, null, "INFO", "执行成功，共采集/写入 312 条", now.minusMinutes(3).toString());

        // 失败的 Python 节点执行（演示诊断）
        String failId = "EXEC-SEED-0002";
        String failStart = now.minusMinutes(35).toString();
        String traceback = "Traceback (most recent call last):\n  File \"<stdin>\", line 2, in <module>\n"
                + "ValueError: could not convert string to float: 'N/A'";
        executions.saveExecution(new Execution(failId, "FLOW-OEE", 1, "schedule", "FAILED",
                failStart, now.minusMinutes(34).toString(), 0L, traceback, null));
        seedNode(failId, "n22", "source", "SUCCESS", 520, "[]",
                "[{\"reg40001\":\"N/A\",\"reg40002\":\"93.4\"}]", null);
        seedNode(failId, "n23", "code", "FAILED", 210,
                "[{\"reg40001\":\"N/A\"}]", null, traceback);
        seedLog(failId, "n23", "ERROR", "节点失败：计算 OEE - " + traceback, failStart);

        // 较早的一条成功执行，让趋势图有两个点
        String oldId = "EXEC-SEED-0003";
        String oldStart = now.minusHours(2).toString();
        executions.saveExecution(new Execution(oldId, "FLOW-FS", 1, "manual", "SUCCESS",
                oldStart, now.minusHours(2).plusSeconds(2).toString(), 120L, null, null));
        seedNode(oldId, "n52", "source", "SUCCESS", 380, "[]",
                "[{\"simulated\":true,\"rows\":[]}]", null);
        seedLog(oldId, null, "INFO", "执行成功，共采集/写入 120 条", oldStart);
    }

    private void seedNode(String executionId, String nodeId, String type, String status,
                          long durationMs, String input, String output, String error) {
        executions.saveNode(new ExecutionNode(IdUtil.next(), executionId, nodeId, type, "1",
                status, durationMs, input, output, error, 0));
    }

    private void seedLog(String executionId, String nodeId, String level, String message, String ts) {
        long seq = executions.maxSeq(executionId) + 1;
        executions.saveLog(new ExecutionLog(IdUtil.next(), executionId, nodeId == null ? "" : nodeId,
                seq, level, message, ts));
    }

    private void saveFlow(String id, String projectId, String name, String description,
                          FlowGraph graph, String now, long records, String successRate) {
        // nextRun 先置空占位，调度器启动后第一次扫描会算出真实的下次执行时间
        flows.saveFlow(new Flow(id, projectId, name, description, "active",
                JsonUtil.stringify(graph), 1, "—", now, records, successRate, now, now));
    }

    private GraphNode node(String id, String type, String subtype, String title,
                           Map<String, Object> config, int x, int y) {
        return new GraphNode(id, type, subtype, "1", title, Map.of("x", x, "y", y), config);
    }

    private GraphEdge edge(String id, String source, String target) {
        return new GraphEdge(id, source, target, "out", "in");
    }
}
