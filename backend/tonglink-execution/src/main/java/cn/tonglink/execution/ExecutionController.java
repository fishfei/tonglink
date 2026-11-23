/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.execution;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Execution;
import cn.tonglink.model.entity.ExecutionLog;
import cn.tonglink.model.entity.ExecutionNode;
import cn.tonglink.repository.ExecutionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行接口：手动运行、执行列表与详情、节点时间线、日志。
 *
 * <p>{@code POST /flows/{id}/run} 挂在执行模块而不是流程模块，是因为它的核心语义是
 * 「产生一次执行」而非修改流程定义。</p>
 */
@RestController
public class ExecutionController {

    private final ExecutionService service;
    private final ExecutionRepository repo;

    public ExecutionController(ExecutionService service, ExecutionRepository repo) {
        this.service = service;
        this.repo = repo;
    }

    /**
     * 手动运行流程。
     *
     * @param body 可选 {@code {triggerType, idempotencyKey}}
     * @return 新建的执行记录（PENDING），前端据此轮询状态或订阅 WebSocket
     */
    @PostMapping("/api/v1/flows/{id}/run")
    public Result<Execution> run(@PathVariable String id,
                                 @RequestBody(required = false) Map<String, String> body) {
        String trigger = body == null ? "manual" : body.getOrDefault("triggerType", "manual");
        String key = body == null ? null : body.get("idempotencyKey");
        return Result.ok(service.submit(id, trigger, key));
    }

    /** 执行列表，支持按流程与状态过滤。 */
    @GetMapping("/api/v1/executions")
    public Result<List<Execution>> list(@RequestParam(required = false) String flowId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(repo.executions(flowId, status, Math.min(Math.max(limit, 1), 1000)));
    }

    /**
     * 分页查询执行记录。
     *
     * <p>执行日志页默认走这个接口：一次只取一页，避免历史执行几千条时整表渲染卡死浏览器。
     * 旧的 {@code /executions} 保留，供看板与脚本做小批量拉取。</p>
     */
    @GetMapping("/api/v1/executions/page")
    public Result<Map<String, Object>> page(@RequestParam(required = false) String flowId,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String tag,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(Math.max(size, 1), 200);
        long total = repo.count(flowId, status, tag);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Execution e : repo.executionsPage(flowId, status, safePage, safeSize, tag)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.id());
            item.put("flowId", e.flowId());
            item.put("flowVersion", e.flowVersion());
            item.put("triggerType", e.triggerType());
            item.put("status", e.status());
            item.put("startTime", e.startTime());
            item.put("endTime", e.endTime());
            item.put("records", e.records());
            item.put("errorSummary", e.errorSummary());
            item.put("tags", repo.tags(e.id()));
            items.add(item);
        }
        Map<String, Object> data = Map.of(
                "items", items,
                "total", total,
                "page", safePage,
                "size", safeSize,
                "totalPages", Math.max(1, (total + safeSize - 1) / safeSize));
        return Result.ok(data);
    }

    /** 给执行记录打标签（用于日志检索与列表标识）。 */
    @PostMapping("/api/v1/executions/{id}/tags")
    public Result<List<String>> saveTags(@PathVariable String id,
                                          @RequestBody Map<String, List<String>> body) {
        if (repo.execution(id) == null) {
            throw new ApiException(Codes.NOT_FOUND, "执行记录不存在");
        }
        List<String> tags = body == null ? List.of() : body.getOrDefault("tags", List.of());
        repo.setTags(id, tags);
        return Result.ok(repo.tags(id));
    }

    /** 执行详情。 */
    @GetMapping("/api/v1/executions/{id}")
    public Result<Execution> get(@PathVariable String id) {
        Execution e = repo.execution(id);
        if (e == null) {
            throw new ApiException(Codes.NOT_FOUND, "执行记录不存在");
        }
        return Result.ok(e);
    }

    /** 节点时间线。 */
    @GetMapping("/api/v1/executions/{id}/nodes")
    public Result<List<ExecutionNode>> nodes(@PathVariable String id) {
        return Result.ok(repo.nodes(id));
    }

    /** 执行日志，{@code after} 用于增量拉取。 */
    @GetMapping("/api/v1/executions/{id}/logs")
    public Result<List<ExecutionLog>> logs(@PathVariable String id,
                                           @RequestParam(defaultValue = "0") long after,
                                           @RequestParam(defaultValue = "500") int limit) {
        return Result.ok(repo.logs(id, after, Math.min(Math.max(limit, 1), 2000)));
    }
}
