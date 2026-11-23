/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.workflow;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Flow;
import cn.tonglink.model.entity.FlowVersion;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程编排接口。
 *
 * <p>导出文件结构 {@code {kind, version, project, flow}} 同时携带所属项目信息，
 * 便于跨环境迁移时知道流程原本属于哪个项目。</p>
 */
@RestController
@RequestMapping("/api/v1/flows")
public class FlowController {

    private final FlowService service;

    public FlowController(FlowService service) {
        this.service = service;
    }

    /** 列表，可按项目过滤。 */
    @GetMapping
    public Result<List<Flow>> list(@RequestParam(required = false) String projectId) {
        return Result.ok(service.list(projectId));
    }

    /** 单条查询。 */
    @GetMapping("/{id}")
    public Result<Flow> get(@PathVariable String id) {
        return Result.ok(service.get(id));
    }

    /** 新建。 */
    @PostMapping
    public Result<Flow> create(@RequestBody Map<String, Object> body) {
        return Result.ok(service.create(body));
    }

    /** 保存草稿。 */
    @PutMapping("/{id}")
    public Result<Flow> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return Result.ok(service.update(id, body));
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.ok();
    }

    /** 发布新版本（会校验图合法性）。 */
    @PostMapping("/{id}/publish")
    public Result<FlowVersion> publish(@PathVariable String id) {
        return Result.ok(service.publish(id));
    }

    /** 下线流程：停止自动调度，保留版本历史。 */
    @PostMapping("/{id}/unpublish")
    public Result<Flow> unpublish(@PathVariable String id) {
        return Result.ok(service.unpublish(id));
    }

    /** 版本历史。 */
    @GetMapping("/{id}/versions")
    public Result<List<FlowVersion>> versions(@PathVariable String id) {
        return Result.ok(service.versions(id));
    }

    /** 导出单个流程，文件名取流程名称。 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        Flow f = service.get(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tonglink-flow");
        payload.put("version", 1);
        payload.put("project", Map.of("id", f.projectId() == null ? "" : f.projectId()));
        payload.put("flow", JsonUtil.parse(f.graphJson(), Map.class));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + safeName(f.name()) + ".json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtil.stringify(payload).getBytes(StandardCharsets.UTF_8));
    }

    /** 导入流程 JSON。 */
    @PostMapping("/import")
    public Result<Flow> importFlow(@RequestBody Map<String, Object> payload) {
        return Result.ok(service.importFlow(payload));
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "flow" : cleaned;
    }
}
