/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.flow.FlowGraph;
import cn.tonglink.model.flow.GraphEdge;
import cn.tonglink.model.flow.GraphNode;
import cn.tonglink.model.flow.NodeDefinition;
import cn.tonglink.node.NodeRegistry;
import cn.tonglink.repository.ExecutionRepository;
import cn.tonglink.repository.FlowRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 能力：自然语言生成流程、执行失败诊断、数据异常问答。
 *
 * <p>默认使用本地规则引擎（零依赖、离线可用、响应稳定）。在「设置 → AI 模型」页保存
 * 大模型连接信息后，生成流程、失败诊断与问答改用 OpenAI 兼容模型；未配置或调用失败时
 * 自动回退本地规则引擎。模型连接信息只来自设置页，不读取环境变量。</p>
 *
 * <p>问答无论走模型还是规则引擎，都会先读取平台真实的流程与执行记录作为上下文，
 * 因此「当前采集任务运行情况如何」这类问题能被具体回答，而不是空泛推脱。</p>
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
    private final FlowRepository flows;
    private final AiSettingsService settings;
    private final LlmClient llmClient;
    private final NodeRegistry nodeRegistry;

    public AiService(ExecutionRepository executions, FlowRepository flows,
                     AiSettingsService settings, LlmClient llmClient, NodeRegistry nodeRegistry) {
        this.executions = executions;
        this.flows = flows;
        this.settings = settings;
        this.llmClient = llmClient;
        this.nodeRegistry = nodeRegistry;
    }

    /**
     * 按自然语言描述生成流程草稿。
     *
     * @param prompt    用户描述
     * @param projectId 归属项目，空则落到默认项目
     * @return {@code {flow, source, explanation}}；flow 符合前端可直接渲染的图结构
     */
    public Map<String, Object> generateFlow(String prompt, String projectId) {
        AiSettingsService.EffectiveConfig config = settings.effective();
        if (config.enabled()) {
            try {
                String system = flowSystemPrompt();
                String reply = callLlm(config, system, "用户描述：" + prompt);
                Map<String, Object> parsed = JsonUtil.parse(extractJson(reply), Map.class);
                if (parsed != null && parsed.get("nodes") != null) {
                    // 模型输出只当作"意图草稿"：subtype 可能不存在、连线可能缺失或端点对不上，
                    // 必须规范化成平台真实节点并按顺序串联，否则会出现节点双击提示不存在、无连线。
                    FlowGraph graph = normalizeGraph(parsed, prompt, projectId);
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("flow", graph);
                    result.put("source", "llm");
                    result.put("provider", config.provider());
                    result.put("explanation", "由大模型（" + config.provider()
                            + "）生成，已按平台节点类型校正并补齐连线，请确认节点配置后保存。");
                    return result;
                }
            } catch (Exception e) {
                log.warn("LLM 生成失败，回退到本地规则引擎：{}", e.getMessage(), e);
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
        // 用户用英文描述时兜底成中文名称，保证生成的流程名与节点名在中文界面里可读
        if (!hasChinese(name)) {
            name = (source == null ? "数据" : source.title()) + " → " + outputs.get(0).title();
        }
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

        // 配置了大模型时先用模型诊断；失败或解析不出结构则回退下面的本地规则。
        AiSettingsService.EffectiveConfig config = settings.effective();
        if (config.enabled()) {
            try {
                String prompt = "你是 IoT 数据采集平台的执行失败诊断专家。请根据以下执行信息"
                        + "给出诊断，只返回 JSON，格式为 {\"severity\":\"error|warning\","
                        + "\"rootCause\":\"一句话根因\",\"evidence\":[\"证据\"],\"steps\":[\"修复步骤\"]}。"
                        + "\n执行ID：" + executionId
                        + "\n流程ID：" + execution.flowId()
                        + "\n执行状态：" + execution.status()
                        + "\n失败节点：" + (failed == null ? "未定位" : failed.nodeId() + "（" + failed.nodeType() + "）")
                        + "\n错误信息：" + (error == null ? "无" : error);
                String reply = callLlm(config, prompt);
                Map<String, Object> parsed = JsonUtil.parse(extractJson(reply), Map.class);
                if (parsed != null && parsed.get("rootCause") != null) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("severity", parsed.getOrDefault("severity", "error"));
                    result.put("rootCause", parsed.get("rootCause"));
                    result.put("evidence", parsed.getOrDefault("evidence", List.of()));
                    result.put("steps", parsed.getOrDefault("steps", List.of()));
                    result.put("source", "llm");
                    result.put("provider", config.provider());
                    return result;
                }
            } catch (Exception e) {
                log.warn("LLM 诊断失败，回退到本地规则：{}", e.getMessage());
            }
        }

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
        result.put("source", "local-rules");
        return result;
    }

    /**
     * 数据与运行情况问答。
     *
     * @param question 用户问题
     * @return {@code {answer, source}}
     */
    public Map<String, Object> ask(String question) {
        RuntimeContext context = buildContext();
        AiSettingsService.EffectiveConfig config = settings.effective();
        if (config.enabled()) {
            try {
                String reply = llmClient.chat(config.endpoint(), config.apiKey(), config.model(),
                        assistantPrompt(context), question == null ? "" : question);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("answer", reply);
                result.put("source", "llm");
                result.put("provider", config.provider());
                return result;
            } catch (Exception e) {
                log.warn("LLM 问答失败，回退到本地规则：{}", e.getMessage());
            }
        }
        return Map.of("answer", localAnswer(question, context), "source", "local-rules");
    }

    /**
     * 流式问答：模型每产出一段就回调 {@code onDelta}，前端可逐段渲染。
     *
     * <p>模型不可用或流中途失败时追加一段本地规则回答，保证用户始终拿得到结果。
     * 返回的 map 描述实际来源，供前端展示标签。</p>
     */
    public Map<String, Object> askStream(String question, Consumer<String> onDelta) {
        RuntimeContext context = buildContext();
        AiSettingsService.EffectiveConfig config = settings.effective();
        if (config.enabled()) {
            try {
                llmClient.stream(config.endpoint(), config.apiKey(), config.model(),
                        assistantPrompt(context), question == null ? "" : question, onDelta);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("source", "llm");
                result.put("provider", config.provider());
                return result;
            } catch (Exception e) {
                log.warn("LLM 流式问答失败，回退到本地规则：{}", e.getMessage());
                onDelta.accept("\n\n[模型调用失败：" + safeMessage(e) + "，以下由本地规则回答]\n\n");
            }
        }
        onDelta.accept(localAnswer(question, context));
        return Map.of("source", "local-rules");
    }

    /** 平台问答的系统提示词：明确告知模型它已拿到实时数据，禁止推脱或编造。 */
    private String assistantPrompt(RuntimeContext context) {
        return "你是 TongLink Studio（IT/OT 数据采集与编排平台）的内置助手。"
                + "下面【平台运行数据】是系统实时读取的数据库快照，你可以且只能依据它回答。"
                + "严禁回答「没有连接权限」「无法访问实时数据」「我没有监控系统」这类推脱话术；"
                + "若数据中没有相关信息，就明确说当前数据中未找到，不要编造具体数值。"
                + "回答用中文，先给结论再给数字，必要时给出下一步建议，控制在 300 字以内。\n"
                + "【平台运行数据】\n" + context.overview() + "\n" + String.join("\n", context.recent());
    }

    /** 本地规则引擎：基于真实上下文回答，不再使用写死的演示数据。 */
    private String localAnswer(String question, RuntimeContext context) {
        String text = question == null ? "" : question.toLowerCase();
        StringBuilder answer = new StringBuilder();
        if (has(text, "失败", "错误", "报错", "诊断")) {
            if (context.failed() == 0) {
                answer.append("最近 ").append(context.recent().size()).append(" 次执行中没有失败记录。");
            } else {
                answer.append("最近 ").append(context.recent().size()).append(" 次执行中有 ")
                        .append(context.failed()).append(" 次失败或超时：\n");
                context.recent().stream().filter(l -> l.contains("FAILED") || l.contains("TIMEOUT"))
                        .limit(5).forEach(l -> answer.append("- ").append(l).append("\n"));
                answer.append("可在执行日志页选中记录点击「AI 诊断」查看根因。");
            }
        } else if (has(text, "异常", "温度", "高温")) {
            List<String> temperature = context.recent().stream()
                    .filter(l -> has(l.toLowerCase(), "温度", "temperature", "异常"))
                    .limit(5).toList();
            if (temperature.isEmpty()) {
                answer.append("最近 ").append(context.recent().size()).append(" 次执行记录中未发现温度异常相关记录。");
            } else {
                answer.append("最近执行记录中与温度/异常相关的内容：\n");
                temperature.forEach(l -> answer.append("- ").append(l).append("\n"));
            }
        } else if (has(text, "流程", "采集", "运行", "任务", "情况", "状态")) {
            answer.append(context.overview()).append("\n");
            context.recent().stream().limit(5).forEach(l -> answer.append("- ").append(l).append("\n"));
            answer.append("完整数据请查看数据看板与执行日志页。");
        } else {
            answer.append("我可以基于平台真实数据回答流程运行、采集任务、失败诊断与异常问题。\n")
                    .append(context.overview());
        }
        return answer.toString();
    }

    /** 从流程与执行表组装问答上下文，避免模型凭空回答。 */
    private RuntimeContext buildContext() {
        List<Flow> all = flows.flows();
        List<Execution> recent = executions.executions(null, null, 20);
        long active = all.stream().filter(f -> "active".equalsIgnoreCase(f.status())).count();
        long success = recent.stream().filter(e -> "SUCCESS".equals(e.status())).count();
        String rate = recent.isEmpty() ? "无执行记录" : String.format("%.1f%%", success * 100.0 / recent.size());
        long failed = recent.stream().filter(e -> "FAILED".equals(e.status()) || "TIMEOUT".equals(e.status())).count();
        long records = all.stream().mapToLong(Flow::records).sum();

        Map<String, String> names = new LinkedHashMap<>();
        for (Flow f : all) {
            names.put(f.id(), f.name());
        }
        List<String> lines = new ArrayList<>();
        for (Execution e : recent) {
            String line = "[" + e.startTime() + "] " + names.getOrDefault(e.flowId(), e.flowId())
                    + " " + e.status() + "，采集 " + e.records() + " 条";
            if (e.errorSummary() != null && !e.errorSummary().isBlank()) {
                line += "，错误：" + clip(e.errorSummary(), 120);
            }
            lines.add(line);
        }
        List<String> nextRuns = all.stream()
                .filter(f -> f.nextRun() != null && !f.nextRun().isBlank() && !"—".equals(f.nextRun()))
                .sorted((a, b) -> a.nextRun().compareTo(b.nextRun()))
                .limit(5)
                .map(f -> f.name() + " 下次 " + f.nextRun())
                .toList();
        List<String> topFlows = all.stream()
                .sorted((a, b) -> Long.compare(b.records(), a.records()))
                .limit(5)
                .map(f -> f.name() + "（" + f.records() + " 条）")
                .toList();

        String overview = "流程总数 " + all.size() + "，启用 " + active + "，累计采集 " + records
                + " 条；最近 " + recent.size() + " 次执行成功率 " + rate + "，失败/超时 " + failed + " 次。"
                + (topFlows.isEmpty() ? "" : " 采集量前 5 的流程：" + String.join("、", topFlows) + "。")
                + (nextRuns.isEmpty() ? "" : " 即将执行：" + String.join("；", nextRuns) + "。");
        return new RuntimeContext(overview, lines, failed);
    }

    private record RuntimeContext(String overview, List<String> recent, long failed) {
    }

    private static String clip(String text, int max) {
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : clip(message, 200);
    }

    // ---------------- 内部工具 ----------------

    /** 用当前生效配置调用 OpenAI 兼容接口，返回首个 choice 的文本。 */
    private String callLlm(AiSettingsService.EffectiveConfig config, String prompt) {
        return llmClient.chat(config.endpoint(), config.apiKey(), config.model(), prompt);
    }

    /** 带系统提示词的模型调用，用于要求固定输出结构的场景。 */
    private String callLlm(AiSettingsService.EffectiveConfig config, String system, String prompt) {
        return llmClient.chat(config.endpoint(), config.apiKey(), config.model(), system, prompt);
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

    /**
     * 生成流程的系统提示词：把平台真实可用的节点类型列给模型，从源头减少幻觉。
     * 强制要求连线，并说明所有子节点必须来自白名单，避免生成 email/weather 之类不存在的节点。
     */
    private String flowSystemPrompt() {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (NodeDefinition def : nodeRegistry.definitions()) {
            byType.computeIfAbsent(def.type(), k -> new ArrayList<>()).add(def.subtype());
        }
        StringBuilder allowed = new StringBuilder();
        byType.forEach((type, subs) -> allowed.append(type).append(':')
                .append(String.join("/", subs)).append("；"));
        StringBuilder titles = new StringBuilder();
        for (NodeDefinition def : nodeRegistry.definitions()) {
            titles.append(def.type()).append(':').append(def.subtype())
                    .append('=').append(def.title()).append('；');
        }
        return "你是 IoT 数据采集平台的流程生成器。根据用户描述生成 TongLink Studio 流程 JSON，"
                + "只返回 JSON、不要解释。顶层结构 {\"id\",\"name\",\"projectId\",\"nodes\",\"connections\"}。"
                + "每个节点含 id/type/subtype/title/position{x,y}/config。"
                + "节点的 type 只能是 trigger/source/code/logic/output 之一，subtype 必须取自这个白名单："
                + allowed + "\n"
                + "顶层 name 和每个节点的 title 必须使用中文，禁止把英文 subtype 当作展示名。"
                + "节点 title 优先使用这个中文名对照：" + titles + "\n"
                + "流程 name 用一句简短中文概括用户描述，例如「每天8点采集温度并邮件告警」。\n"
                + "严禁使用白名单之外的 subtype（例如 email、weather、send_mail 都不存在；"
                + "发邮件请用 type=output,subtype=alert 并在 config.channel 写「邮件」；"
                + "查天气/调用外部接口请用 type=source,subtype=http）。"
                + "connections 是数组，每条形如 {\"id\",\"source\",\"target\",\"sourceHandle\",\"targetHandle\"}，"
                + "source/target 必须是某个节点的 id，按执行顺序把节点全部串起来；"
                + "条件节点 logic:condition 用 sourceHandle=true 接下游。不要输出孤立节点。";
    }

    /**
     * 把模型返回的松散 JSON 规范化成平台可直接渲染、可落库的标准流程图。
     *
     * <p>模型经常生成不存在的 subtype（email/weather）、缺失连线、连线端点引用不存在的
     * 节点，直接透传给前端会导致双击提示「节点不存在」、画布没有连线。这里做三件事：
     * 1) subtype 不在注册表时按关键词别名或同类默认节点纠正；2) 重新分配规范 id 与坐标；
     * 3) 丢弃模型给的连线，按节点顺序重新串联，保证每条边端点都是真实存在的节点。</p>
     */
    private FlowGraph normalizeGraph(Map<String, Object> parsed, String prompt, String projectId) {
        Set<String> known = new HashSet<>();
        for (NodeDefinition def : nodeRegistry.definitions()) {
            known.add(def.type() + ":" + def.subtype());
        }

        Object rawNodes = parsed.get("nodes");
        List<GraphNode> nodes = new ArrayList<>();
        if (rawNodes instanceof List<?> list) {
            int index = 0;
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> raw)) {
                    continue;
                }
                String type = lower(str(raw.get("type")));
                String subtype = lower(str(raw.get("subtype")));
                String[] fixed = resolveNodeType(type, subtype, known);
                type = fixed[0];
                subtype = fixed[1];

                Map<String, Object> config = raw.get("config") instanceof Map<?, ?> c
                        ? stringMap(c) : new LinkedHashMap<>();
                // 发邮件类意图统一落到 alert 节点的 channel=邮件，避免模型自造 email 节点
                if ("alert".equals(subtype) && mentionsMail(subtype + " " + str(raw.get("title")))) {
                    config.put("channel", "邮件");
                }
                String title = str(raw.get("title"));
                // 模型常把 subtype 英文标识直接当标题（如 schedule、http），统一换成中文展示名
                if (title.isBlank() || !hasChinese(title)) {
                    title = defaultTitle(type, subtype);
                }
                Map<String, Object> position = new LinkedHashMap<>();
                if (raw.get("position") instanceof Map<?, ?> p) {
                    position.put("x", number(p.get("x"), X_START + index * X_STEP));
                    position.put("y", number(p.get("y"), DEFAULT_Y));
                } else {
                    position.put("x", X_START + index * X_STEP);
                    position.put("y", DEFAULT_Y);
                }
                nodes.add(new GraphNode("n" + index, type, subtype, "1", title, position, config));
                index++;
            }
        }

        // 没有任何可用节点时不生成空流程，交回调用方回退本地规则引擎
        if (nodes.isEmpty()) {
            throw new IllegalStateException("模型未返回可用节点");
        }

        // 按顺序重新串联，条件节点走 true 分支；丢弃模型给的对不上端点的连线
        List<GraphEdge> edges = new ArrayList<>();
        for (int i = 1; i < nodes.size(); i++) {
            GraphNode from = nodes.get(i - 1);
            String handle = "condition".equals(from.subtype()) ? "true" : "out";
            edges.add(new GraphEdge("e" + (i - 1), from.id(), nodes.get(i).id(), handle, "in"));
        }

        String name = str(parsed.get("name"));
        if (!hasChinese(name)) {
            name = flowName(prompt, null, null);
        }
        if (!hasChinese(name)) {
            name = "自动生成的数据采集流程";
        }
        String id = str(parsed.get("id"));
        if (!id.startsWith("FLOW-")) {
            id = IdUtil.prefix("FLOW");
        }
        return new FlowGraph(1, id, name,
                projectId == null || projectId.isBlank() ? "P-001" : projectId,
                Map.of("overlapPolicy", "SKIP"), nodes, edges);
    }

    /** 纠正模型给的 type/subtype 到注册表里的合法组合。 */
    private String[] resolveNodeType(String type, String subtype, Set<String> known) {
        String raw = type + ":" + subtype;
        if (known.contains(raw)) {
            return new String[]{type, subtype};
        }
        // 关键词别名：模型常用自然语言 subtype，映射到平台真实节点
        String alias = aliasSubtype(type, subtype);
        if (alias != null && known.contains(alias)) {
            return alias.split(":", 2);
        }
        // 再按大类兜底到该大类第一个可用节点，保证类型合法
        String fallback = switch (type) {
            case "trigger" -> firstOf(known, "trigger", "schedule", "manual", "kafka", "mqtt");
            case "source" -> firstOf(known, "source", "http", "mysql", "opcua", "modbus");
            case "code" -> firstOf(known, "code", "nodejs", "python");
            case "logic" -> firstOf(known, "logic", "condition", "filter", "delay", "merge");
            case "output" -> firstOf(known, "output", "alert", "http", "timeseries", "feishu_bitable");
            default -> null;
        };
        return fallback != null ? fallback.split(":", 2) : new String[]{"source", "http"};
    }

    private String firstOf(Set<String> known, String type, String... candidates) {
        for (String candidate : candidates) {
            if (known.contains(type + ":" + candidate)) {
                return type + ":" + candidate;
            }
        }
        return null;
    }

    /** 模型常见的口语化 subtype → 平台合法 subtype。 */
    private String aliasSubtype(String type, String subtype) {
        String s = subtype.replaceAll("[^a-z0-9]", "");
        if (s.contains("mail") || s.contains("email") || s.contains("邮件")) {
            return "output:alert";
        }
        if (s.contains("weather") || s.contains("天气") || s.equals("http") || s.contains("api")) {
            return knownType("source", "http");
        }
        if (s.contains("sql") || s.contains("mysql") || s.contains("database") || s.contains("db")) {
            return knownType("source", "mysql");
        }
        if (s.contains("condition") || s.contains("if") || s.contains("判断") || s.contains("分支")) {
            return knownType("logic", "condition");
        }
        if (s.contains("python")) {
            return "code:python";
        }
        if (s.contains("code") || s.contains("script") || s.contains("js")) {
            return "code:nodejs";
        }
        if (s.contains("cron") || s.contains("timer") || s.contains("schedule") || s.contains("定时")) {
            return "trigger:schedule";
        }
        return null;
    }

    /** 别名映射里优先复用注册表实际存在的 subtype（兼容节点包裁剪）。 */
    private String knownType(String type, String subtype) {
        for (NodeDefinition def : nodeRegistry.definitions()) {
            if (def.type().equals(type) && def.subtype().equals(subtype)) {
                return type + ":" + subtype;
            }
        }
        return null;
    }

    private boolean mentionsMail(String text) {
        String t = text.toLowerCase();
        return t.contains("mail") || t.contains("邮件") || t.contains("email");
    }

    private String defaultTitle(String type, String subtype) {
        for (NodeDefinition def : nodeRegistry.definitions()) {
            if (def.type().equals(type) && def.subtype().equals(subtype)) {
                return def.title();
            }
        }
        return subtype.isEmpty() ? type : subtype;
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    private static String lower(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }

    /** 文本中是否含汉字；用于把模型给出的英文标题兜底成平台中文展示名。 */
    private static boolean hasChinese(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
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
        String to = output == null ? "处理" : output.title().replace("写入", "").replace("推送", "");
        // 长描述截取前半段，保证名称可读且不至于过长
        String head = raw.length() > 24 ? raw.substring(0, 24) : raw;
        return head.isEmpty() ? from + " → " + to : head;
    }

    /** 整数阈值不显示小数点，避免生成 {{ $json.value > 85.0 }}。 */
    private String trimNumber(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
