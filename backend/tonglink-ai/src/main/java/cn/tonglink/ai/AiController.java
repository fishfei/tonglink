/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.Result;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 接口：生成流程、失败诊断、数据问答。
 *
 * <p>三个入口分别对应流程编排页的「AI 生成流程」、执行日志页的「AI 诊断」
 * 与 AI 助手页的对话框。</p>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService service;

    public AiController(AiService service) {
        this.service = service;
    }

    /** 自然语言生成流程草稿。 */
    @PostMapping("/generate-flow")
    public Result<Map<String, Object>> generate(@RequestBody Map<String, String> body) {
        return Result.ok(service.generateFlow(body.get("prompt"), body.get("projectId")));
    }

    /** 执行失败诊断。 */
    @PostMapping("/diagnose")
    public Result<Map<String, Object>> diagnose(@RequestBody Map<String, String> body) {
        return Result.ok(service.diagnose(body.get("executionId")));
    }

    /** 数据与运行情况问答。 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return Result.ok(service.ask(body.get("question")));
    }
}
