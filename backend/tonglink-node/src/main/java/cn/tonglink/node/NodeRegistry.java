/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.node;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.model.entity.NodePackage;
import cn.tonglink.model.flow.NodeDefinition;
import cn.tonglink.repository.FlowRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 节点注册中心。
 *
 * <p>把「内置节点定义（JSON）」与「已安装的节点包（数据库）」合并成统一的节点目录，
 * 前端只调一个 {@code /nodes/definitions} 就能拿到节点库分组、图标、颜色与配置 Schema。</p>
 *
 * <p>注册表以 {@code type:subtype} 为键放在内存里，安装/卸载后即时生效，无需重启。</p>
 */
@Service
public class NodeRegistry {

    private static final Pattern TYPE_PATTERN = Pattern.compile("[a-z]+");
    private static final Pattern SUBTYPE_PATTERN = Pattern.compile("[a-z0-9_]+");

    private final FlowRepository repo;

    /** 键为 {@code type:subtype}，值为节点定义；内置在前、扩展在后。 */
    private final Map<String, NodeDefinition> definitions = new LinkedHashMap<>();

    /** 可安装的扩展节点市场。 */
    private final List<Map<String, Object>> market = new ArrayList<>();

    /** 节点包的运行时配置，键为 type:subtype。安装后由执行器直接消费。 */
    private final Map<String, Map<String, Object>> runtimes = new LinkedHashMap<>();

    public NodeRegistry(FlowRepository repo) {
        this.repo = repo;
        loadBuiltIn();
        loadInstalled();
    }

    /** 从 classpath 加载内置节点：catalog 提供图标/颜色，schemas 提供配置表单。 */
    @SuppressWarnings("unchecked")
    private void loadBuiltIn() {
        try (InputStream in = new ClassPathResource("node-definitions.json").getInputStream()) {
            Map<String, Object> root = JsonUtil.mapper()
                    .readValue(in, new TypeReference<Map<String, Object>>() {
                    });

            // 先把 catalog 展平成 type:subtype -> 元数据，便于给每个 schema 补齐展示信息
            Map<String, Map<String, Object>> meta = new LinkedHashMap<>();
            Object catalog = root.get("catalog");
            if (catalog instanceof List<?> groups) {
                for (Object groupObj : groups) {
                    Map<String, Object> group = JsonUtil.toMap(groupObj);
                    Object items = group.get("items");
                    if (!(items instanceof List<?> list)) {
                        continue;
                    }
                    for (Object itemObj : list) {
                        Map<String, Object> item = JsonUtil.toMap(itemObj);
                        meta.put(item.get("type") + ":" + item.get("subtype"), item);
                    }
                }
            }

            Map<String, Object> schemas = JsonUtil.toMap(root.get("schemas"));
            schemas.forEach((key, schemaObj) -> {
                String[] parts = key.split(":", 2);
                if (parts.length != 2) {
                    return;
                }
                Map<String, Object> item = meta.getOrDefault(key, Map.of());
                Map<String, Object> schema = JsonUtil.toMap(schemaObj);
                definitions.put(key, new NodeDefinition(
                        parts[0],
                        parts[1],
                        str(schema.get("title"), parts[1]),
                        str(item.get("icon"), "puzzle"),
                        str(item.get("color"), "#64748B"),
                        str(item.get("desc"), str(schema.get("note"), "")),
                        str(item.get("author"), "TongLink 官方"),
                        str(item.get("version"), "1.0.0"),
                        true,
                        schema));
            });

            Object marketList = root.get("market");
            if (marketList instanceof List<?> list) {
                for (Object each : list) {
                    market.add(JsonUtil.toMap(each));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("加载内置节点定义失败", e);
        }
    }

    /** 把数据库里已安装的节点包注册进内存。 */
    private void loadInstalled() {
        for (NodePackage pkg : repo.nodePackages()) {
            if (!"installed".equals(pkg.status())) {
                continue;
            }
            Map<String, Object> manifest = JsonUtil.parse(pkg.manifestJson(),
                    new TypeReference<Map<String, Object>>() {
                    });
            if (manifest != null) {
                registerPackage(manifest, false);
            }
        }
    }

    /**
     * 注册节点包中的节点定义。
     *
     * <p>只接受最新清单格式：每个节点必须有 {@code schema} 对象与 {@code runtime.kind}，
     * 安装时严格校验，加载已安装包时同样校验；不再为老版本包放宽规则。</p>
     *
     * @param manifest 节点包清单，须含 {@code nodes} 数组
     * @param builtIn  是否内置
     * @return 本次注册的节点定义
     */
    public List<NodeDefinition> registerPackage(Map<String, Object> manifest, boolean builtIn) {
        List<NodeDefinition> result = new ArrayList<>();
        Object raw = manifest.get("nodes");
        if (!(raw instanceof List<?> nodesRaw) || nodesRaw.isEmpty()) {
            throw new ApiException(Codes.VALIDATION, "节点包清单缺少 nodes 定义");
        }
        List<Object> nodes = new ArrayList<>(nodesRaw);
        Map<String, Object> packageRuntime = manifest.get("runtime") instanceof Map<?, ?>
                ? JsonUtil.toMap(manifest.get("runtime")) : Map.of();
        for (Object nodeObj : nodes) {
            Map<String, Object> node = JsonUtil.toMap(nodeObj);
            String type = str(node.get("type"), "");
            String subtype = str(node.get("subtype"), "");
            if (!TYPE_PATTERN.matcher(type).matches() || !SUBTYPE_PATTERN.matcher(subtype).matches()) {
                throw new ApiException(Codes.VALIDATION, "节点 type/subtype 格式不合法：" + type + ":" + subtype);
            }
            Map<String, Object> runtime = node.get("runtime") instanceof Map<?, ?>
                    ? JsonUtil.toMap(node.get("runtime")) : packageRuntime;
            String kind = str(runtime.get("kind"), "");
            if (kind.isBlank()) {
                throw new ApiException(Codes.VALIDATION,
                        "节点 " + type + ":" + subtype + " 缺少 runtime.kind，安装后无法真实执行");
            }
            if (!List.of("http", "script", "redis", "wecom", "influxdb").contains(kind)) {
                throw new ApiException(Codes.VALIDATION, "不支持的运行时类型：" + kind);
            }
            if (!(node.get("schema") instanceof Map<?, ?>)) {
                throw new ApiException(Codes.VALIDATION,
                        "节点 " + type + ":" + subtype + " 缺少 schema 定义");
            }
            runtimes.put(type + ":" + subtype, runtime);
            Map<String, Object> schema = JsonUtil.toMap(node.get("schema"));
            NodeDefinition def = new NodeDefinition(
                    type,
                    subtype,
                    str(node.get("title"), subtype),
                    str(node.get("icon"), "puzzle"),
                    str(node.get("color"), "#64748B"),
                    str(node.get("desc"), ""),
                    str(node.get("author"), "社区贡献"),
                    str(node.get("version"), "1.0.0"),
                    builtIn,
                    schema);
            definitions.put(type + ":" + subtype, def);
            result.add(def);
        }
        return result;
    }

    /** 全部节点定义，按 type、subtype 排序，保证前端分组稳定。 */
    public List<NodeDefinition> definitions() {
        return definitions.values().stream()
                .sorted(Comparator.comparing(NodeDefinition::type).thenComparing(NodeDefinition::subtype))
                .toList();
    }

    /**
     * 取单个节点定义。
     *
     * @throws ApiException 节点未安装时返回 404，流程保存校验会据此提示
     */
    public NodeDefinition definition(String type, String subtype) {
        NodeDefinition def = definitions.get(type + ":" + subtype);
        if (def == null) {
            throw new ApiException(Codes.NOT_FOUND, "未安装的节点类型：" + type + ":" + subtype);
        }
        return def;
    }

    /** 节点包安装后的真实运行时配置；未安装的节点返回空 Map。 */
    public Map<String, Object> runtimeFor(String type, String subtype) {
        Map<String, Object> runtime = runtimes.get(type + ":" + subtype);
        return runtime == null ? Map.of() : runtime;
    }

    /** 已安装的扩展包及其运行时摘要，供节点管理页展示。 */
    public List<Map<String, Object>> installedPackages() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NodePackage pkg : repo.nodePackages()) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("packageId", pkg.packageId());
            view.put("version", pkg.version());
            view.put("status", pkg.status());
            view.put("installedAt", pkg.installedAt());
            Map<String, Object> manifest = JsonUtil.parse(pkg.manifestJson(),
                    new TypeReference<Map<String, Object>>() {});
            if (manifest != null) {
                Object runtime = manifest.get("runtime");
                view.put("runtime", runtime == null ? Map.of() : runtime);
                Object nodes = manifest.get("nodes");
                if (nodes instanceof List<?> list) {
                    view.put("nodeCount", list.size());
                } else {
                    view.put("nodeCount", 1);
                }
            }
            result.add(view);
        }
        return result;
    }

    /** 已安装节点（与 {@link #definitions()} 等价，语义化别名）。 */
    public List<NodeDefinition> available() {
        return definitions();
    }

    /** 可安装的扩展节点列表。 */
    public List<Map<String, Object>> market() {
        return market;
    }

    /**
     * 从市场安装节点包。
     *
     * @param packageId 市场条目的 key
     */
    public List<NodeDefinition> install(String packageId) {
        Map<String, Object> item = market.stream()
                .filter(m -> packageId != null && packageId.equals(String.valueOf(m.get("key"))))
                .findFirst()
                .orElseThrow(() -> new ApiException(Codes.NOT_FOUND, "节点包不存在：" + packageId));
        Map<String, Object> manifest;
        if (item.get("manifest") instanceof Map<?, ?> m) {
            manifest = JsonUtil.toMap(m);
        } else {
            manifest = new LinkedHashMap<>();
            manifest.put("id", packageId);
            manifest.put("version", str(item.get("version"), "1.0.0"));
            manifest.put("nodes", List.of(item));
        }
        return installManifest(manifest, packageId);
    }

    /**
     * 安装节点包：注册定义 + 落库安装记录。
     *
     * @param manifest  清单
     * @param packageId 包标识，为空时取清单中的 id
     */
    public List<NodeDefinition> installManifest(Map<String, Object> manifest, String packageId) {
        String id = packageId == null || packageId.isBlank()
                ? str(manifest.get("id"), IdUtil.prefix("PKG"))
                : packageId;
        String version = str(manifest.get("version"), "1.0.0");
        List<NodeDefinition> defs = registerPackage(manifest, false);
        repo.saveNodePackage(new NodePackage(IdUtil.next(), id, version, JsonUtil.stringify(manifest),
                "installed", LocalDateTime.now().withNano(0).toString()));
        return defs;
    }

    /** 卸载节点包：可按 packageId 或节点 subtype 删除，并重建扩展节点注册表。 */
    public void uninstall(String packageId) {
        String target = repo.nodePackages().stream()
                .filter(p -> p.packageId().equals(packageId) || manifestHasSubtype(p, packageId))
                .map(NodePackage::packageId)
                .findFirst()
                .orElseThrow(() -> new ApiException(Codes.NOT_FOUND, "未安装的节点包：" + packageId));
        repo.deleteNodePackage(target);
        definitions.entrySet().removeIf(e -> !e.getValue().builtIn());
        runtimes.clear();
        loadInstalled();
    }

    private boolean manifestHasSubtype(NodePackage pkg, String subtype) {
        Map<String, Object> manifest = JsonUtil.parse(pkg.manifestJson(),
                new TypeReference<Map<String, Object>>() {});
        if (manifest == null) return false;
        if (subtype.equals(str(manifest.get("subtype"), ""))) return true;
        Object nodes = manifest.get("nodes");
        if (!(nodes instanceof List<?> list)) return false;
        return list.stream().map(JsonUtil::toMap)
                .anyMatch(n -> subtype.equals(str(n.get("subtype"), "")));
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }
}
