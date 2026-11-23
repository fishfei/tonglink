/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.taskrunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Task Runner 测试：Node.js / Python 代码节点真实执行，以及错误传播。
 *
 * <p>依赖本机运行时；缺少对应运行时时用 {@code assumeTrue} 跳过而不是失败，
 * 这样 CI 与开发机都能跑出有意义的结果。</p>
 */
class CodeRunnerServiceTest {

    private static boolean nodeAvailable;
    private static boolean pythonAvailable;

    private final CodeRunnerService runner = new CodeRunnerService("node", "python3", "python");

    @BeforeAll
    static void detectRuntimes() {
        nodeAvailable = available("node");
        pythonAvailable = available("python3") || available("python");
    }

    /** Node.js 节点：输入行经过脚本处理后再返回。 */
    @Test
    void 执行NodeJs代码节点() {
        assumeTrue(nodeAvailable, "本机未安装 Node.js");
        List<Map<String, Object>> input = List.of(Map.of("value", 21));

        CodeRunnerService.RunResult result = runner.run("nodejs",
                "return $input.map(x => ({...x, doubled: x.value * 2}));",
                input, Map.of("TEMP_THRESHOLD", "85"), 30);

        assertThat(result.ok()).as(result.error()).isTrue();
        assertThat(result.output()).hasSize(1);
        assertThat(result.output().get(0)).containsEntry("doubled", 42);
    }

    /** Python 节点：使用 _items 读取输入，把结果赋给 result。 */
    @Test
    void 执行Python代码节点() {
        assumeTrue(pythonAvailable, "本机未安装 Python");
        List<Map<String, Object>> input = List.of(Map.of("value", 5), Map.of("value", 7));

        CodeRunnerService.RunResult result = runner.run("python",
                "result = [dict(item, total=item['value'] * 3) for item in _items]",
                input, Map.of(), 30);

        assertThat(result.ok()).as(result.error()).isTrue();
        assertThat(result.output()).hasSize(2);
        assertThat(result.output().get(1)).containsEntry("total", 21);
    }

    /** 脚本抛异常时必须返回失败与可读原因，而不是让主进程崩溃。 */
    @Test
    void 脚本异常返回失败原因() {
        assumeTrue(nodeAvailable, "本机未安装 Node.js");

        CodeRunnerService.RunResult result = runner.run("nodejs",
                "throw new Error('boom-should-be-captured');", List.of(), Map.of(), 30);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("boom-should-be-captured");
        assertThat(result.output()).isEmpty();
    }

    /** 探测命令是否可用。 */
    private static boolean available(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            return p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
