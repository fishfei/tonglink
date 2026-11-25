/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.JsonUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * 数据源接口：连接参数与认证凭据统一维护。
 *
 * <p>除标准 CRUD 外提供凭据类型目录、连通性测试与 JSON 导入导出。
 * 所有返回给前端的数据源都经过视图转换，密码字段只给掩码。</p>
 */
@RestController
@RequestMapping("/api/v1/datasources")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    /** 列表；{@code protocol} 为可选的模糊过滤条件。 */
    @GetMapping
    public Result<List<Map<String, Object>>> list(@RequestParam(required = false) String protocol) {
        List<DataSource> all = service.list();
        if (protocol != null && !protocol.isBlank()) {
            String keyword = protocol.toLowerCase();
            all = all.stream().filter(s -> s.protocol().toLowerCase().contains(keyword)).toList();
        }
        return Result.ok(all.stream().map(service::view).toList());
    }

    /** 凭据类型目录：前端据此渲染不同数据源的认证表单。 */
    @GetMapping("/credential-types")
    public Result<List<Map<String, Object>>> credentialTypes() {
        return Result.ok(service.credentialTypes());
    }

    /** 单条查询。 */
    @GetMapping("/{id}")
    public Result<Map<String, Object>> get(@PathVariable String id) {
        return Result.ok(service.view(service.get(id)));
    }

    /** 新建。 */
    @PostMapping
    public Result<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return Result.ok(service.view(service.create(body)));
    }

    /** 更新。 */
    @PutMapping("/{id}")
    public Result<Map<String, Object>> update(@PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        return Result.ok(service.view(service.update(id, body)));
    }

    /** 删除。 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        service.delete(id);
        return Result.ok();
    }

    /** 测试已保存数据源的连通性。 */
    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable String id) {
        return Result.ok(service.test(id));
    }

    /** 保存前用表单里的字段直接测试连通性，不修改数据库。 */
    @PostMapping("/test")
    public Result<Map<String, Object>> testDraft(@RequestBody Map<String, Object> body) {
        return Result.ok(service.testDraft(body));
    }

    /** 导出全部数据源；凭据密码仍以密文导出，导入后可继续使用。 */
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAll() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tonglink-sources");
        payload.put("version", 1);
        payload.put("items", service.list());
        return download("datasources.json", JsonUtil.stringify(payload));
    }

    /** 导出单个数据源，文件名取数据源名称并清洗非法字符。 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportOne(@PathVariable String id) {
        DataSource s = service.get(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", "tonglink-source");
        payload.put("version", 1);
        payload.put("item", s);
        return download(safeName(s.name()) + ".json", JsonUtil.stringify(payload));
    }

    /** 导入：兼容「单个 item」与「批量 items」两种文件，重新生成 ID。 */
    @PostMapping("/import")
    public Result<List<Map<String, Object>>> importJson(@RequestBody Map<String, Object> payload) {
        List<Map<String, Object>> created = new ArrayList<>();
        Object item = payload.get("item");
        if (item != null) {
            Map<String, Object> body = JsonUtil.toMap(item);
            body.remove("id");
            created.add(service.view(service.create(body)));
        }
        Object items = payload.get("items");
        if (items instanceof List<?> list) {
            for (Object each : list) {
                Map<String, Object> body = JsonUtil.toMap(each);
                body.remove("id");
                created.add(service.view(service.create(body)));
            }
        }
        if (created.isEmpty()) {
            throw new ApiException(Codes.VALIDATION, "未找到可导入的数据源");
        }
        return Result.ok(created);
    }

    /** 组装附件响应；文件名用 RFC 5987 编码，兼容中文名。 */
    private ResponseEntity<byte[]> download(String filename, String json) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeName(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return cleaned.isBlank() ? "datasource" : cleaned;
    }
}
