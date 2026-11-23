/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.node;

import cn.tonglink.common.JsonUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.flow.NodeDefinition;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 节点管理接口。
 *
 * <p>{@code /nodes/definitions} 是前端节点库与属性面板的唯一数据源，
 * 该接口不需要登录即可预览，便于未登录时展示平台能力。</p>
 */
@RestController
@RequestMapping("/api/v1/nodes")
public class NodeController {

    private final NodeRegistry registry;

    public NodeController(NodeRegistry registry) {
        this.registry = registry;
    }

    /** 已安装节点定义（含内置与扩展）。 */
    @GetMapping("/definitions")
    public Result<List<NodeDefinition>> definitions() {
        return Result.ok(registry.definitions());
    }

    /** 扩展节点市场。 */
    @GetMapping("/market")
    public Result<List<Map<String, Object>>> market() {
        return Result.ok(registry.market());
    }

    /**
     * 安装节点包。
     *
     * <p>请求体两种形态：{@code {packageId}} 从市场安装；
     * {@code {manifest}} 安装本地节点包 JSON。</p>
     */
    @PostMapping("/install")
    public Result<List<NodeDefinition>> install(@RequestBody Map<String, Object> body) {
        Object manifest = body.get("manifest");
        if (manifest instanceof Map<?, ?>) {
            return Result.ok(registry.installManifest(JsonUtil.toMap(manifest), str(body.get("packageId"))));
        }
        return Result.ok(registry.install(str(body.get("packageId"))));
    }

    /** 卸载节点包，路径参数为节点 subtype（前端按 subtype 传）。 */
    @DeleteMapping("/{packageId}")
    public Result<Void> uninstall(@PathVariable String packageId) {
        registry.uninstall(packageId);
        return Result.ok();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
