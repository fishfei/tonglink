/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.Result;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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

    /** 数据与运行情况问答（一次性响应，供非流式调用方使用）。 */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody Map<String, String> body) {
        return Result.ok(service.ask(body.get("question")));
    }

    /**
     * 流式问答：逐段下发 delta，最后一条 meta 说明实际来源。
     *
     * <p>用异步线程执行模型调用，避免占住 Tomcat 请求线程；前端收到内容即可渲染，
     * 不必等到整个回答生成完毕才看到结果。</p>
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody Map<String, String> body) {
        SseEmitter emitter = new SseEmitter(180_000L);
        CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> meta = service.askStream(body.get("question"), chunk -> {
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(chunk));
                    } catch (IOException e) {
                        throw new IllegalStateException("下发流式内容失败", e);
                    }
                });
                emitter.send(SseEmitter.event().name("meta").data(meta));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
