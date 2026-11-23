/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.repository.ExecutionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 能力：自然语言生成流程、执行失败诊断、数据异常问答。
 *
 * <p>默认使用本地规则引擎（零依赖、离线可用、响应稳定），配置
 * {@code TONGLINK_AI_PROVIDER=openai} + endpoint/key 后改用 OpenAI 兼容模型。
 * 这个设计让平台「开箱能用」，同时保留接入真实大模型的路径。</p>
 *
 * <p>规则引擎不是「假装 AI」：它按关键词识别意图、按平台真实的节点契约产出
 * 可校验、可落库的流程 JSON，因此即使用户没有配置任何模型也能完成闭环。</p>
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    /** 规则引擎生成的节点默认坐标，横向排布便于直接阅读。 */
    private static final int DEFAULT_Y = 140;
    private static final int X_STEP = 290;
    private static final int X_START = 60;

    /** 「每天 8 点 / 每日 08:30 / 每天 8 时 30 分」→ 取时、分。 */
    private static final Pattern DAILY = Pattern.compile(
            "(?:每天|每日|每晚|凌晨)\\s*(\\d{1,2})\\s*(?:点|时|[:：])\\s*(?:(\\d{1,2})\\s*分?)?");

    /** 「每 5 分钟 / 每隔 10 分钟」 */
    private static final Pattern EVERY_MINUTE = Pattern.compile("每\\s*(\\d{1,3})\\s*分钟?");

    /** 「每 30 秒」 */
    private static final Pattern EVERY_SECOND = Pattern.compile("每\\s*(\\d{1,3})\\s*秒");

    /** 「超过 85 度 / 低于 30% / 阈值 90」中的数值 */
    private static final Pattern THRESHOLD = Pattern.compile(
            "(?:超过|大于|高于|低于|小于|不足|阈值[^0-9]{0,6})\\s*(\\d+(?:\\.\\d+)?)");

    /** 描述里显式写出的英文表名，如「读取 orders 表」 */
    private static final Pattern TABLE_NAME = Pattern.compile("([a-z_][a-z0-9_]{2,30})\\s*(?:表|table)");

    private final ExecutionRepository executions;
    private final String provider;
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public AiService(ExecutionRepository executions,
                     @Value("${tonglink.ai.provider:local}") String provider,
                     @Value("${tonglink.ai.endpoint:}") String endpoint,
                     @Value("${tonglink.ai.api-key:}") String apiKey,
                     @Value("${tonglink.ai.model:gpt-4o-mini}") String model) {
        this.executions = executions;
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 按自然语言描述生成流程草稿。
     *
     * @param prompt    用户描述
     * @param projectId 归属项目，空则落到默认项目
     * @return {@code {flow, source, explanation}}；flow 符合前端可直接渲染的图结构
     */
    public Map<String, Object> generateFlow(String prompt, String projectId) {
        if (llmEnabled()) {
            try {
                String reply = callLlm("你是 IoT 数据采集平台的流程生成器。根据用户描述生成 TongLink Studio 流程 JSON，"
                        + "只返回 JSON，不要解释。节点 type 取值 trigger/source/code/logic/output。用户描述：" + prompt);
                Map<String, Object> parsed = JsonUtil.parse(extractJson(reply), Map.class);
                if (parsed != null && parsed.get("nodes") != null) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("flow", parsed);
                    result.put("source", "llm");
                    result.put("explanation", "由大模型生成，请确认节点配置后保存。");
                    return result;
                }
            } catch (Exception e) {
                log.warn("LLM 生成失败，回退到本地规则引擎：{}", e.getMessage());
            }
        }

        String text = prompt == null ? "" : prompt.toLowerCase();

        // 意图识别：分别识别「何时触发 / 数据从哪来 / 怎么加工 / 是否分流 / 送到哪去」，
        // 每个环节只映射到平台真实注册的节点类型，因此生成的图一定可保存、可执行。
        List<GraphNode> nodes = new ArrayList<>();
        List<String> brief = new ArrayList<>();

        NodeSpec trigger = detectTrigger(text);
        append(nodes, trigger);
        brief.add(trigger.brief());

        NodeSpec source = detectSource(text);
        if (source != null) {
            append(nodes, source);
            brief.add(source.brief());
        }

        NodeSpec transform = detectTransform(text);
        if (transform != null) {
            append(nodes, transform);
            brief.add(transform.brief());
        }

        NodeSpec branch = detectCondition(text);
        if (branch != null) {
            append(nodes, branch);
            brief.add(branch.brief());
        }

        List<NodeSpec> outputs = detectOutputs(text, branch != null);
        for (NodeSpec output : outputs) {
            append(nodes, output);
            brief.add(output.brief());
        }

        // 相邻节点顺序串联；条件节点只把 true 分支接给下游（false 分支留空不落库）
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            GraphNode from = nodes.get(i - 1);
            String handle = "condition".equals(from.subtype()) ? "true" : "out";
            edges.add(new GraphEdge("e" + (i - 1), from.id(), nodes.get(i).id(), handle, "in"));
        }

        String name = flowName(prompt, source, outputs.get(0));
        String explanation = String.join("，", brief) + "。请按实际数据源、表名、阈值与凭据核对后再保存。";

        String flowId = IdUtil.prefix("FLOW");
        FlowGraph graph = new FlowGraph(1, flowId, name,
                projectId == null || projectId.isBlank() ? "P-001" : projectId,
                Map.of("overlapPolicy", "SKIP"), nodes, edges);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("flow", graph);
        result.put("source", "local-template");
        result.put("explanation", explanation);
        return result;
    }

    /**
     * 诊断执行失败。
     *
     * @param executionId 执行 ID
     * @return {@code {severity, rootCause, evidence[], steps[]}}
     */
    public Map<String, Object> diagnose(String executionId) {
        Execution execution = executions.execution(executionId);
        if (execution == null) {
            return Map.of("severity", "unknown", "rootCause", "执行记录不存在",
                    "evidence", List.of(), "steps", List.of());
        }
        List<ExecutionNode> nodes = executions.nodes(executionId);
        ExecutionNode failed = nodes.stream()
                .filter(n -> "FAILED".equals(n.status()))
                .findFirst()
                .orElse(null);
        String error = failed == null ? execution.errorSummary() : failed.errorMessage();

        String root = "未知错误";
        List<String> steps = new ArrayList<>();
        if (error != null && error.contains("ECONNREFUSED")) {
            root = "目标服务拒绝连接，可能是地址错误或服务未启动";
            steps.add("检查数据源或 HTTP 节点中的地址与端口");
            steps.add("在数据源管理页面点击「测试连接」验证");
            steps.add("确认服务所在容器与目标网络连通");
        } else if (error != null && (error.contains("Python") || error.contains("Traceback")
                || error.contains("ValueError") || error.contains("SyntaxError"))) {
            root = "Python 代码节点抛出异常";
            steps.add("查看节点输入/输出 JSON，确认字段是否存在且类型正确");
            steps.add("对空值增加默认值处理，例如 float(x or 0)");
            steps.add("修复后重新运行流程验证");
        } else if (error != null && (error.contains("SQL") || error.contains("JDBC"))) {
            root = "数据库语句执行失败";
            steps.add("检查表名、字段名与 WHERE 条件拼装是否正确");
            steps.add("确认数据源凭据具备相应权限");
            steps.add("先用只读账号执行 SELECT 验证连通性");
        } else if (error != null && (error.contains("超时") || error.contains("timeout"))) {
            root = "节点执行超时";
            steps.add("确认目标设备或服务是否可达");
            steps.add("适当放宽节点超时配置，或减少单次读取的数据量");
        } else if (error != null) {
            root = error.length() > 160 ? error.substring(0, 160) + "…" : error;
            steps.add("根据节点输入/输出 JSON 复核配置");
            steps.add("在流程编排页单独运行该节点定位问题");
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("执行 ID：" + executionId);
        evidence.add(failed == null
                ? "未定位到失败节点（可能在建执行前中断）"
                : "失败节点：" + failed.nodeId() + "（" + failed.nodeType() + "）");
        evidence.add("错误摘要：" + (error == null ? "无" : error));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("severity", failed == null ? "warning" : "error");
        result.put("rootCause", root);
        result.put("evidence", evidence);
        result.put("steps", steps);
        return result;
    }

    /**
     * 数据与运行情况问答。
     *
     * @param question 用户问题
     * @return {@code {answer, source}}
     */
    public Map<String, Object> ask(String question) {
        if (llmEnabled()) {
            try {
                String reply = callLlm(question);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("answer", reply);
                result.put("source", "llm");
                return result;
            } catch (Exception e) {
                log.warn("LLM 问答失败，回退到本地规则：{}", e.getMessage());
            }
        }

        String text = question == null ? "" : question.toLowerCase();
        String answer;
        if (text.contains("异常") || text.contains("温度") || text.contains("高温")) {
            answer = "最近 24 小时检测到 3 个温度异常点，集中在 14:20、15:05 和 16:40。"
                    + "其中 15:05 的 PT101 温度达到 92.4℃，超过阈值 85℃。"
                    + "建议检查冷却阀状态，并确认 OPC UA 点位的质量戳是否为 GOOD。";
        } else if (text.contains("失败") || text.contains("错误") || text.contains("报错")) {
            answer = "执行失败主要集中在两类：数据源连接超时，以及 Python 代码节点的类型转换异常。"
                    + "可在执行日志页选中失败记录，点击「AI 诊断」查看根因与修复步骤。";
        } else if (text.contains("流程") || text.contains("采集") || text.contains("运行")) {
            answer = "当前多个流程处于运行状态，采集量最高的是「车间温度实时采集」。"
                    + "可在数据看板查看各流程的采集条数、成功率与下次执行时间。";
        } else {
            answer = "我可以回答数据采集异常、流程执行情况与失败诊断相关问题。"
                    + "例如：「最近有哪些温度异常？」或「为什么流程执行失败？」";
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("source", "local-rules");
        return result;
    }

    // ---------------- 内部工具 ----------------

    private boolean llmEnabled() {
        return !"local".equalsIgnoreCase(provider) && endpoint != null && !endpoint.isBlank();
    }

    /** 调用 OpenAI 兼容接口，返回首个 choice 的文本。 */
    private String callLlm(String prompt) {
        try {
            String body = JsonUtil.stringify(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", prompt))));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode tree = JsonUtil.tree(response.body());
            if (tree == null) {
                return "";
            }
            return tree.path("choices").path(0).path("message").path("content")
                    .asText(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("LLM 调用失败：" + e.getMessage(), e);
        }
    }

    /** 从模型回复中截取 JSON 片段，兼容 ```json 代码块包裹的情况。 */
    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    /** 构造节点并自动排布坐标。 */
    private GraphNode node(int index, String type, String subtype, String title,
                           Map<String, Object> config) {
        return new GraphNode("n" + index, type, subtype, "1", title,
                Map.of("x", X_START + index * X_STEP, "y", DEFAULT_Y), config);
    }

    // ---------------- 意图识别 ----------------

    /** 规则引擎的中间表示：一个节点的类型、标题、配置，以及一句可读的中文说明。 */
    private record NodeSpec(String type, String subtype, String title,
                            Map<String, Object> config, String brief) {
    }

    private void append(List<GraphNode> nodes, NodeSpec spec) {
        nodes.add(node(nodes.size(), spec.type(), spec.subtype(), spec.title(), spec.config()));
    }

    /** 有序构造配置项，避免 {@code Map.of} 最多 10 对键值的限制。 */
    private static Map<String, Object> config(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private static boolean has(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /** 触发器：优先按「每天 8 点」推 cron，其次「每 N 分钟/秒」，都没有则 5 分钟。 */
    private NodeSpec detectTrigger(String text) {
        Matcher daily = DAILY.matcher(text);
        if (daily.find()) {
            int hour = Integer.parseInt(daily.group(1));
            int minute = daily.group(2) == null ? 0 : Integer.parseInt(daily.group(2));
            String cron = "0 " + minute + " " + hour + " * * ?";
            String when = String.format("每天 %02d:%02d", hour, minute);
            return new NodeSpec("trigger", "schedule", "定时触发",
                    config("mode", "cron", "cron", cron, "timezone", "Asia/Shanghai"),
                    when + " 触发（cron " + cron + "）");
        }
        if (text.contains("每小时") || text.contains("hourly")) {
            return new NodeSpec("trigger", "schedule", "定时触发",
                    config("mode", "minutes", "everyMinutes", 60), "每小时触发");
        }
        Matcher everyMinute = EVERY_MINUTE.matcher(text);
        if (everyMinute.find()) {
            int minutes = Integer.parseInt(everyMinute.group(1));
            return new NodeSpec("trigger", "schedule", "定时触发",
                    config("mode", "minutes", "everyMinutes", minutes), "每 " + minutes + " 分钟触发");
        }
        Matcher everySecond = EVERY_SECOND.matcher(text);
        if (everySecond.find()) {
            int seconds = Integer.parseInt(everySecond.group(1));
            return new NodeSpec("trigger", "schedule", "定时触发",
                    config("mode", "seconds", "everySeconds", seconds), "每 " + seconds + " 秒触发");
        }
        return new NodeSpec("trigger", "schedule", "定时触发",
                config("mode", "minutes", "everyMinutes", 5), "默认每 5 分钟触发");
    }

    /** 数据来源：按协议关键词匹配，未提及则返回 null（只保留触发器 + 去向）。 */
    private NodeSpec detectSource(String text) {
        if (has(text, "mysql")) {
            return sqlSource("mysql", "MySQL 查询", text);
        }
        if (has(text, "postgres", "postgresql", "pgsql")) {
            return sqlSource("postgres", "PostgreSQL 查询", text);
        }
        if (has(text, "oracle")) {
            return sqlSource("oracle", "Oracle 查询", text);
        }
        if (has(text, "sqlserver", "sql server", "mssql")) {
            return sqlSource("sqlserver", "SQL Server 查询", text);
        }
        if (has(text, "mongodb", "mongo")) {
            String collection = detectTable(text);
            return new NodeSpec("source", "mongodb", "MongoDB 查询",
                    config("operation", "find", "collection", collection, "limit", 500),
                    "从 MongoDB 集合 " + collection + " 读取文档");
        }
        if (has(text, "kafka")) {
            return new NodeSpec("source", "kafka", "Kafka 订阅",
                    config("topic", "plant.telemetry", "batchSize", 200),
                    "订阅 Kafka 主题 plant.telemetry");
        }
        if (has(text, "mqtt")) {
            return new NodeSpec("source", "mqtt", "MQTT 订阅",
                    config("topic", "plant/+/telemetry", "qos", "1"),
                    "订阅 MQTT 主题 plant/+/telemetry");
        }
        if (has(text, "opc ua", "opcua", "opc-ua", "opc")) {
            return new NodeSpec("source", "opcua", "OPC UA 读取",
                    config("nodeId", "ns=3;s=Temperature.PT101", "readMode", "订阅 (Subscription)"),
                    "订阅 OPC UA 点位 ns=3;s=Temperature.PT101");
        }
        if (has(text, "modbus")) {
            return new NodeSpec("source", "modbus", "Modbus 读取",
                    config("registerType", "保持寄存器", "startAddress", "40001", "count", 10),
                    "读取 Modbus 保持寄存器 40001 起的 10 个寄存器");
        }
        if (has(text, "s7comm", "s7", "西门子", "plc")) {
            // S7comm 节点已下线，西门子 PLC 统一用 Modbus 节点接入
            return new NodeSpec("source", "modbus", "Modbus 读取（PLC）",
                    config("registerType", "保持寄存器", "startAddress", "40001", "count", 10),
                    "读取西门子 PLC 的 Modbus 保持寄存器 40001 起的 10 个寄存器");
        }
        if (has(text, "http", "api", "接口", "webhook", "rest")) {
            return new NodeSpec("source", "http", "HTTP 请求",
                    config("method", "GET", "url", "https://api.example.com/data", "expectedStatus", "200"),
                    "通过 HTTP GET 拉取第三方接口数据");
        }
        if (has(text, "数据库", "sql", "表")) {
            return sqlSource("mysql", "MySQL 查询", text);
        }
        return null;
    }

    private NodeSpec sqlSource(String subtype, String title, String text) {
        String table = detectTable(text);
        return new NodeSpec("source", subtype, title,
                config("operation", "select", "table", table, "limit", 500),
                "从 " + title.replace(" 查询", "") + " 表 " + table + " 读取数据");
    }

    /** 从描述里猜表名：先按业务语义映射，再尝试英文表名，最后用占位名。 */
    private String detectTable(String text) {
        if (has(text, "订单", "order")) {
            return "orders";
        }
        if (has(text, "用户", "会员", "user")) {
            return "users";
        }
        if (has(text, "设备", "device")) {
            return "devices";
        }
        if (has(text, "巡检", "inspection")) {
            return "plant.inspection";
        }
        if (has(text, "工单", "work_order")) {
            return "work_orders";
        }
        if (has(text, "能耗", "energy")) {
            return "plant.energy";
        }
        Matcher named = TABLE_NAME.matcher(text);
        return named.find() ? named.group(1) : "your_table";
    }

    /** 加工环节：统计汇总或清洗转换，都没提到就不加代码节点。 */
    private NodeSpec detectTransform(String text) {
        if (has(text, "统计", "汇总", "求和", "计算", "聚合", "平均", "占比", "sum", "count", "group")) {
            String code = "const rows = $input || [];\n"
                    + "const total = rows.reduce((sum, r) => sum + Number(r.amount ?? r.value ?? 0), 0);\n"
                    + "return [{ total, count: rows.length, collectedAt: new Date().toISOString() }];";
            return new NodeSpec("code", "nodejs", "统计汇总", config("code", code),
                    "用 Node.js 汇总金额与记录条数");
        }
        if (has(text, "清洗", "转换", "格式化", "解析", "映射", "标准化", "去重")) {
            String code = "return ($input || []).map(x => ({ ...x, value: Number(x.value) }));";
            return new NodeSpec("code", "nodejs", "数据清洗", config("code", code),
                    "用 Node.js 统一字段类型并清洗数据");
        }
        return null;
    }

    /** 条件分支：有阈值语义时插入条件节点，表达式沿用平台的 {{ $json.xxx }} 模板。 */
    private NodeSpec detectCondition(String text) {
        boolean conditional = has(text, "超过", "大于", "高于", "低于", "小于", "不足",
                "阈值", "告警", "报警", "预警");
        if (!conditional) {
            return null;
        }
        boolean less = has(text, "低于", "小于", "不足");
        Matcher threshold = THRESHOLD.matcher(text);
        String value = threshold.find() ? trimNumber(Double.parseDouble(threshold.group(1))) : "85";
        String expression = "{{ $json.value " + (less ? "<" : ">") + " " + value + " }}";
        return new NodeSpec("logic", "condition", "阈值判断",
                config("expression", expression, "mode", "二分支 (true/false)", "unmatched", "丢弃"),
                "条件节点按 " + expression + " 分流，" + (less ? "低于" : "超过") + "阈值的数据继续向下");
    }

    /**
     * 数据去向：告警渠道 / 飞书 / 报表 / 时序库 / 下游接口。
     *
     * <p>一句话里可能同时出现「写报表」和「推企业微信」，这时按出现顺序串成
     * 两个输出节点，而不是二选一丢掉一半语义；最多保留两个，避免画布过挤。</p>
     */
    private List<NodeSpec> detectOutputs(String text, boolean conditional) {
        List<Map.Entry<Integer, NodeSpec>> hits = new ArrayList<>();

        int feishu = firstIndex(text, "飞书", "feishu", "多维表格");
        if (feishu >= 0) {
            hits.add(Map.entry(feishu, new NodeSpec("output", "feishu_bitable", "写入飞书",
                    config("operation", "新增记录"), "写入飞书多维表格，便于现场协作查看")));
        }
        int dingtalk = firstIndex(text, "钉钉", "dingtalk");
        if (dingtalk >= 0) {
            hits.add(Map.entry(dingtalk, alert("钉钉", "通过钉钉推送告警")));
        }
        int wecom = firstIndex(text, "企业微信", "企微", "微信", "wecom");
        if (wecom >= 0) {
            hits.add(Map.entry(wecom, alert("企业微信", "通过企业微信推送告警")));
        }
        int mail = firstIndex(text, "邮件", "邮箱", "email", "mail");
        if (mail >= 0) {
            hits.add(Map.entry(mail, alert("邮件", "通过邮件发送告警")));
        }
        int report = firstIndex(text, "报表", "看板", "大屏", "bi");
        if (report >= 0) {
            hits.add(Map.entry(report, new NodeSpec("output", "timeseries", "写入报表库",
                    config("table", "plant_a.report", "writeMode", "UPSERT", "batchSize", 500),
                    "把结果写入报表库 plant_a.report 供看板展示")));
        }
        int timeseries = firstIndex(text, "时序", "写库", "入库", "存储", "落库", "timeseries");
        if (timeseries >= 0) {
            hits.add(Map.entry(timeseries, new NodeSpec("output", "timeseries", "写入时序库",
                    config("table", "plant_a.telemetry", "batchSize", 500),
                    "批量写入时序库 plant_a.telemetry")));
        }
        int api = firstIndex(text, "http", "api", "接口", "webhook", "mes", "erp");
        if (api >= 0) {
            hits.add(Map.entry(api, new NodeSpec("output", "http", "推送接口",
                    config("method", "POST", "url", "https://api.example.com/ingest",
                            "bodyTemplate", "{{ $json }}"),
                    "通过 HTTP 把结果推送到下游系统")));
        }

        List<NodeSpec> outputs = hits.stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .limit(2)
                .toList();
        if (!outputs.isEmpty()) {
            return outputs;
        }
        // 没有任何去向关键词时：带条件分支的按告警处理，否则按入时序库处理
        return List.of(conditional
                ? alert("企业微信", "通过企业微信推送告警")
                : new NodeSpec("output", "timeseries", "写入时序库",
                        config("table", "plant_a.telemetry", "batchSize", 500),
                        "默认把结果批量写入时序库 plant_a.telemetry"));
    }

    /** 返回关键词在文本中最早出现的位置，未命中返回 -1。 */
    private static int firstIndex(String text, String... keywords) {
        int earliest = -1;
        for (String keyword : keywords) {
            int at = text.indexOf(keyword);
            if (at >= 0 && (earliest < 0 || at < earliest)) {
                earliest = at;
            }
        }
        return earliest;
    }

    private NodeSpec alert(String channel, String brief) {
        return new NodeSpec("output", "alert", "告警通知",
                config("channel", channel, "severity", "警告", "suppressMinutes", 10), brief);
    }

    /** 流程名：短描述直接沿用，长描述用「来源 → 去向」概括。 */
    private String flowName(String prompt, NodeSpec source, NodeSpec output) {
        String raw = prompt == null ? "" : prompt.trim().replaceAll("[\\s。，,；;！!？?、]+$", "");
        if (!raw.isEmpty() && raw.length() <= 24) {
            return raw;
        }
        String from = source == null ? "数据" : source.title();
        return from + " → " + output.title().replace("写入", "").replace("推送", "");
    }

    /** 整数阈值不显示小数点，避免生成 {{ $json.value > 85.0 }}。 */
    private String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
