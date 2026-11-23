/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.taskrunner;

import cn.tonglink.common.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 代码节点 Task Runner（内部子进程模式）。
 *
 * <p>实现要点与坑：</p>
 * <ul>
 *   <li><b>不直接读管道</b>：若先 {@code readAllBytes()} 再 {@code waitFor()}，遇到死循环脚本会永久阻塞，
 *       超时形同虚设；这里把 stdout/stderr 重定向到文件，先按超时 {@code waitFor}，超时后强杀再读文件。</li>
 *   <li><b>结果走文件</b>：子进程把结果写到 output.json，主进程只读结构化结果，
 *       避免在标准输出里混入用户 {@code console.log} 造成解析失败。</li>
 *   <li><b>Python 命令回退</b>：Linux 是 {@code python3}、Windows 常是 {@code python}，依次尝试。</li>
 * </ul>
 *
 * <p>隔离级别说明：这是「故障隔离 + 资源限制」，不是强安全边界。运行不可信代码请启用
 * 外部 Runner 容器（见架构文档第 7 节）。</p>
 */
@Service
public class CodeRunnerService {

    private static final Logger log = LoggerFactory.getLogger(CodeRunnerService.class);

    /** 允许的最大输出字符数（约 1MB）。 */
    private static final int MAX_OUTPUT_CHARS = 1024 * 1024;

    /** 错误信息截断长度，避免把整段堆栈写进数据库。 */
    private static final int MAX_ERROR_CHARS = 2000;

    private final List<List<String>> nodeCommands;
    private final List<List<String>> pythonCommands;

    public CodeRunnerService(
            @Value("${tonglink.runner.node-command:node}") String nodeCommand,
            @Value("${tonglink.runner.python-command:python3}") String pythonCommand,
            @Value("${tonglink.runner.python-fallback:python}") String pythonFallback) {
        this.nodeCommands = buildNodeCommands(nodeCommand);
        this.pythonCommands = buildPythonCommands(pythonCommand, pythonFallback);
    }

    /**
     * Node 候选命令：优先用项目内便携版 Node（从当前目录向上找 tools/node），
     * 这样即使从 IDEA 启动、系统 PATH 里只有老版本 Node，也能运行现代语法。
     */
    private static List<List<String>> buildNodeCommands(String configured) {
        List<List<String>> commands = new ArrayList<>();
        if (configured != null && !configured.isBlank() && !"node".equals(configured)) {
            commands.add(List.of(configured));
        }
        Path bundled = findProjectRuntime("node");
        if (bundled != null) {
            commands.add(List.of(bundled.toString()));
        }
        commands.add(List.of("node"));
        return commands;
    }

    /** Python 候选命令：Windows 上 python3 常常不存在，py -3 启动器最可靠。 */
    private static List<List<String>> buildPythonCommands(String primary, String fallback) {
        List<List<String>> commands = new ArrayList<>();
        for (String candidate : List.of(primary, fallback)) {
            if (candidate != null && !candidate.isBlank()) {
                commands.add(List.of(candidate));
            }
        }
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            commands.add(List.of("py", "-3"));
        }
        commands.add(List.of("python3"));
        commands.add(List.of("python"));
        return commands.stream().distinct().toList();
    }

    /** 从工作目录向上最多 6 层查找 tools/node 下的运行时，找不到返回 null。 */
    private static Path findProjectRuntime(String name) {
        try {
            Path dir = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
            for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
                Path win = dir.resolve("tools").resolve(name).resolve(name + ".exe");
                Path unix = dir.resolve("tools").resolve(name).resolve("bin").resolve(name);
                if (Files.isExecutable(win)) return win;
                if (Files.isExecutable(unix)) return unix;
            }
        } catch (Exception e) {
            log.debug("查找项目内 {} 运行时失败：{}", name, e.getMessage());
        }
        return null;
    }

    /**
     * 执行用户代码。
     *
     * @param language       nodejs / python
     * @param userCode       用户脚本
     * @param items          输入行
     * @param vars           变量表
     * @param timeoutSeconds 超时（秒）
     * @return 执行结果；失败时 {@code ok=false} 且 error 有值
     */
    public RunResult run(String language, String userCode, List<Map<String, Object>> items,
                         Map<String, Object> vars, int timeoutSeconds) {
        long start = System.currentTimeMillis();
        Path dir = null;
        try {
            dir = Files.createTempDirectory("tonglink-runner-");
            Path input = dir.resolve("input.json");
            Path output = dir.resolve("output.json");
            Path stdout = dir.resolve("stdout.log");
            Path stderr = dir.resolve("stderr.log");

            Map<String, Object> payload = Map.of(
                    "items", items == null ? List.of() : items,
                    "vars", vars == null ? Map.of() : vars,
                    "env", System.getenv());
            Files.writeString(input, JsonUtil.stringify(payload), StandardCharsets.UTF_8);

            boolean python = "python".equalsIgnoreCase(language);
            String script = python
                    ? pythonScript(userCode, input.toString(), output.toString())
                    : nodeScript(userCode, input.toString(), output.toString());
            Path scriptFile = dir.resolve(python ? "script.py" : "script.js");
            Files.writeString(scriptFile, script, StandardCharsets.UTF_8);

            String failure = execute(python, scriptFile, stdout, stderr, timeoutSeconds);
            // 用户脚本的 print / console.log 走 stdout，异常栈走 stderr；两者都要回传执行日志
            List<String> logs = captureLogs(stdout, stderr);
            if (failure != null) {
                return new RunResult(false, List.of(), failure, logs, elapsed(start));
            }
            if (!Files.exists(output)) {
                return new RunResult(false, List.of(), "Runner 未产生输出文件", logs, elapsed(start));
            }
            String json = Files.readString(output, StandardCharsets.UTF_8);
            if (json.length() > MAX_OUTPUT_CHARS) {
                return new RunResult(false, List.of(), "输出超过 1MB 上限", logs, elapsed(start));
            }
            Map<String, Object> parsed = JsonUtil.parse(json, new TypeReference<Map<String, Object>>() {
            });
            if (parsed == null || !Boolean.TRUE.equals(parsed.get("ok"))) {
                String error = parsed == null ? "输出不是合法 JSON" : String.valueOf(parsed.get("error"));
                return new RunResult(false, List.of(), error, logs, elapsed(start));
            }
            return new RunResult(true, normalizeOutput(parsed.get("output")), null, logs, elapsed(start));
        } catch (Exception e) {
            return new RunResult(false, List.of(), e.getMessage(), List.of(), elapsed(start));
        } finally {
            deleteQuietly(dir);
        }
    }

    /** 把 runner 的返回值统一成行列表：数组逐项，对象包成单行。 */
    private List<Map<String, Object>> normalizeOutput(Object data) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (data instanceof List<?> list) {
            for (Object each : list) {
                out.add(JsonUtil.toMap(each));
            }
        } else if (data instanceof Map<?, ?>) {
            out.add(JsonUtil.toMap(data));
        }
        return out;
    }

    /**
     * 启动子进程并等待。
     *
     * @return null 表示成功；否则返回错误描述
     */
    private String execute(boolean python, Path script, Path stdout, Path stderr, int timeoutSeconds)
            throws IOException, InterruptedException {
        List<List<String>> commands = python ? pythonCommands : nodeCommands;
        Exception last = null;
        String lastFailure = null;
        for (List<String> cmd : commands) {
            Process process = null;
            try {
                List<String> argv = new ArrayList<>(cmd);
                argv.add(script.toString());
                process = new ProcessBuilder(argv)
                        .redirectOutput(stdout.toFile())
                        .redirectError(stderr.toFile())
                        .start();
                boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                    return "执行超时（" + timeoutSeconds + " 秒）";
                }
                if (process.exitValue() != 0) {
                    // 当前候选运行时存在但脚本失败：记住原因并尝试下一个候选
                    // （例如项目便携 Node 不可用时，仍能退回系统 node）
                    lastFailure = trim("Runner 退出码 " + process.exitValue() + "："
                            + readQuietly(stderr, "（无错误输出）"));
                    continue;
                }
                return null;
            } catch (IOException e) {
                // 命令不存在（如 Linux 上没有 python3），换下一个候选命令
                last = e;
                if (process != null) {
                    process.destroyForcibly();
                }
            }
        }
        if (lastFailure != null) {
            return lastFailure;
        }
        throw new IllegalStateException((python ? "Python" : "Node.js")
                + " 运行时不可用：" + (last == null ? "" : last.getMessage()));
    }

    /**
     * Node.js 包装脚本。
     *
     * <p>把用户代码包进 async 函数，支持 {@code await}；约定用 {@code return} 返回结果。
     * 顶层 provide {@code $input} / {@code $json} / {@code $vars} / {@code $env} 四个变量。</p>
     */
    private String nodeScript(String userCode, String inputPath, String outputPath) {
        String body = userCode == null || userCode.isBlank() ? "return $input;" : userCode;
        return """
                const fs = require('fs');
                const payload = JSON.parse(fs.readFileSync(%s, 'utf8'));
                const $input = payload.items || [];
                const $json = $input[0] || {};
                const $vars = payload.vars || {};
                const $env = payload.env || {};

                const __write = (ok, output, error) => fs.writeFileSync(%s,
                  JSON.stringify({ ok, output, error }));

                (async () => {
                  const result = await (async () => {
                %s
                  })();
                  const output = Array.isArray(result) ? result : [result];
                  __write(true, output, null);
                })().catch(e => __write(false, [], String((e && e.stack) || e)));
                """.formatted(jsString(inputPath), jsString(outputPath), body);
    }

    /**
     * Python 包装脚本。
     *
     * <p>提供 {@code _items} / {@code _json} / {@code _vars} / {@code _env}，
     * 约定把结果赋给 {@code result}；异常会把完整 traceback 写回输出，
     * 前端诊断卡正是基于这段堆栈给出根因。</p>
     */
    private String pythonScript(String userCode, String inputPath, String outputPath) {
        String body = userCode == null || userCode.isBlank() ? "result = _items" : userCode;
        return """
                import json, traceback

                with open(%s, encoding='utf-8') as f:
                    payload = json.load(f)

                _items = payload.get('items', [])
                _json = _items[0] if _items else {}
                _vars = payload.get('vars', {})
                _env = payload.get('env', {})
                result = _items

                try:
                %s
                    output = result if isinstance(result, list) else [result]
                    json.dump({'ok': True, 'output': output, 'error': None},
                              open(%s, 'w', encoding='utf-8'))
                except Exception:
                    json.dump({'ok': False, 'output': [], 'error': traceback.format_exc()},
                              open(%s, 'w', encoding='utf-8'))
                """.formatted(pyString(inputPath), indent(body), pyString(outputPath), pyString(outputPath));
    }

    /** 用户代码整体缩进 4 空格，嵌入 Python 的 try 块。 */
    private String indent(String code) {
        return Arrays.stream(code.replace("\r", "").split("\n"))
                .map(line -> "    " + line)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("    result = _items");
    }

    /** 生成 JS 字符串字面量（含引号与转义）。 */
    private String jsString(String value) {
        return JsonUtil.stringify(value);
    }

    /** 生成 Python 字符串字面量：JSON 的双引号字符串同时是合法的 Python 字面量。 */
    private String pyString(String value) {
        return JsonUtil.stringify(value);
    }

    private String readQuietly(Path path, String fallback) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String trim(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, MAX_ERROR_CHARS) + "…";
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 递归删除临时目录，失败只记日志，不影响执行结果。 */
    private void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception e) {
            log.debug("清理临时目录失败：{}", dir, e);
        }
    }

    /** 单个日志来源最多保留的行数，避免刷屏脚本撑爆执行日志表。 */
    private static final int MAX_LOG_LINES = 500;

    /** 读取子进程输出，转成带来源标记的执行日志行。 */
    private List<String> captureLogs(Path stdout, Path stderr) {
        List<String> lines = new ArrayList<>();
        appendLines(lines, stdout, "stdout");
        appendLines(lines, stderr, "stderr");
        return lines;
    }

    private void appendLines(List<String> target, Path path, String source) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (target.size() >= MAX_LOG_LINES) {
                    target.add("[" + source + "] ... 日志已截断（最多 " + MAX_LOG_LINES + " 行）");
                    break;
                }
                if (line.isBlank()) {
                    continue;
                }
                String safe = line.length() <= MAX_ERROR_CHARS
                        ? line : line.substring(0, MAX_ERROR_CHARS) + "…";
                target.add("[" + source + "] " + safe);
            }
        } catch (IOException e) {
            log.debug("读取 {} 日志失败：{}", source, e.getMessage());
        }
    }

    /**
     * 执行结果。
     *
     * @param ok         是否成功
     * @param output     输出行
     * @param error      失败原因（含 traceback）
     * @param logs       用户脚本的 stdout / stderr 日志行
     * @param durationMs 耗时
     */
    public record RunResult(boolean ok, List<Map<String, Object>> output, String error,
                            List<String> logs, long durationMs) {

        /** 兼容未回传日志的调用。 */
        public RunResult(boolean ok, List<Map<String, Object>> output, String error, long durationMs) {
            this(ok, output, error, List.of(), durationMs);
        }
    }
}
