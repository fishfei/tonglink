/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.connector;

import cn.tonglink.taskrunner.CodeRunnerService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 代码节点执行器：把用户脚本交给 Task Runner 子进程执行。
 *
 * <p>放在独立进程中运行是为了「故障隔离」：死循环、内存溢出只会终止子进程，
 * 不会拖垮主应用。这不是强安全边界，生产环境运行不可信代码应启用外部 Runner 容器。</p>
 */
@Component
public class CodeNodeExecutor implements NodeExecutor {

    private final CodeRunnerService runner;

    public CodeNodeExecutor(CodeRunnerService runner) {
        this.runner = runner;
    }

    @Override
    public Set<String> supports() {
        return Set.of("code:nodejs", "code:python");
    }

    @Override
    public ExecResult execute(ExecContext context, Map<String, Object> config,
                              List<Map<String, Object>> input) {
        String language = "python".equals(context.node().subtype()) ? "python" : "nodejs";
        int timeout = parseSeconds(config.getOrDefault("timeout", "300s"));
        CodeRunnerService.RunResult result = runner.run(language,
                String.valueOf(config.getOrDefault("code", "")), input, context.vars(), timeout);
        if (!result.ok()) {
            throw new NodeLogException("代码节点执行失败：" + result.error(), result.logs());
        }
        return new ExecResult(result.output(), null, result.logs());
    }

    /** 把 {@code "300s"} 这类配置解析成秒，限制在 1~1800 之间。 */
    private int parseSeconds(Object value) {
        String text = String.valueOf(value).replace("s", "").trim();
        try {
            return Math.max(1, Math.min(1800, Integer.parseInt(text)));
        } catch (NumberFormatException e) {
            return 300;
        }
    }
}
