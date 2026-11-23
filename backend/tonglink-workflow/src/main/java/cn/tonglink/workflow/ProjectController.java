/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.workflow;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Project;
import cn.tonglink.repository.FlowRepository;
import cn.tonglink.repository.MetaRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目管理接口。
 *
 * <p>列表额外返回 {@code flowCount}，前端顶栏下拉可直接显示「项目名（N 个流程）」，
 * 避免为每个项目再发一次查询。</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final MetaRepository meta;
    private final FlowRepository flows;

    public ProjectController(MetaRepository meta, FlowRepository flows) {
        this.meta = meta;
        this.flows = flows;
    }

    /** 项目列表（含流程数）。 */
    @GetMapping
    public Result<List<Map<String, Object>>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Project p : meta.projects()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", p.id());
            view.put("name", p.name());
            view.put("code", p.code() == null ? "" : p.code());
            view.put("color", p.color() == null ? "#64748B" : p.color());
            view.put("description", p.description() == null ? "" : p.description());
            view.put("flowCount", flows.flowsByProject(p.id()).size());
            view.put("createdAt", p.createdAt() == null ? "" : p.createdAt());
            result.add(view);
        }
        return Result.ok(result);
    }

    /** 新建项目。 */
    @PostMapping
    public Result<Project> create(@RequestBody Map<String, Object> body) {
        String now = meta.now();
        Project p = new Project(
                str(body.get("id"), IdUtil.prefix("P")),
                str(body.get("name"), "未命名项目"),
                str(body.get("code"), ""),
                str(body.get("color"), "#2563EB"),
                str(body.get("description"), ""),
                now,
                now);
        meta.saveProject(p);
        return Result.ok(p);
    }

    /** 更新项目。 */
    @PutMapping("/{id}")
    public Result<Project> update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Project old = meta.project(id);
        if (old == null) {
            throw new ApiException(Codes.NOT_FOUND, "项目不存在");
        }
        Project p = new Project(id,
                str(body.get("name"), old.name()),
                str(body.get("code"), nullToEmpty(old.code())),
                str(body.get("color"), old.color() == null ? "#2563EB" : old.color()),
                str(body.get("description"), nullToEmpty(old.description())),
                old.createdAt(),
                meta.now());
        meta.updateProject(p);
        return Result.ok(p);
    }

    /**
     * 删除项目。
     *
     * @throws ApiException 项目下仍有流程时返回 409，避免误删导致流程变成孤儿
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        if (!flows.flowsByProject(id).isEmpty()) {
            throw new ApiException(Codes.CONFLICT, "项目下仍有流程，请先删除或移动流程");
        }
        meta.deleteProject(id);
        return Result.ok();
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
