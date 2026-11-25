/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Variable;
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
 * 变量接口。
 *
 * <p>项目全新起步，变量统一以明文存储与返回，不保留任何历史密文兼容逻辑。
 * 变量的 {@code scope} 为逗号分隔的项目 ID；{@code all} 表示全部项目可用。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private final MetaRepository repo;

    public ConfigController(MetaRepository repo) {
        this.repo = repo;
    }

    /** 变量列表，值原样返回明文。 */
    @GetMapping("/variables")
    public Result<List<Map<String, Object>>> variables() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Variable v : repo.variables()) {
            result.add(variableView(v));
        }
        return Result.ok(result);
    }

    /** 新建变量。 */
    @PostMapping("/variables")
    public Result<Map<String, Object>> createVariable(@RequestBody Map<String, Object> body) {
        String now = repo.now();
        Variable v = new Variable(
                str(body.get("id"), IdUtil.prefix("V")),
                str(body.get("name"), ""),
                str(body.get("key"), ""),
                str(body.get("env"), "prod"),
                str(body.get("value"), ""),
                str(body.get("scope"), "global"),
                str(body.get("description"), ""),
                now,
                now);
        repo.saveVariable(v);
        return Result.ok(variableView(v));
    }

    /** 更新变量；请求体中不带 value 才表示保持原值，带空串表示显式清空。 */
    @PutMapping("/variables/{id}")
    public Result<Map<String, Object>> updateVariable(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        Variable old = repo.variable(id);
        if (old == null) {
            throw new ApiException(Codes.NOT_FOUND, "变量不存在");
        }
        String value = body.containsKey("value")
                ? String.valueOf(body.get("value") == null ? "" : body.get("value"))
                : nullToEmpty(old.valueCipher());
        Variable v = new Variable(id,
                str(body.get("name"), old.name()),
                str(body.get("key"), old.key()),
                str(body.get("env"), old.env()),
                value,
                str(body.get("scope"), old.scope()),
                str(body.get("description"), nullToEmpty(old.description())),
                old.createdAt(),
                repo.now());
        repo.updateVariable(v);
        return Result.ok(variableView(v));
    }

    /** 删除变量。 */
    @DeleteMapping("/variables/{id}")
    public Result<Void> deleteVariable(@PathVariable String id) {
        repo.deleteVariable(id);
        return Result.ok();
    }

    /** 变量视图：值就是数据库里保存的明文。 */
    private Map<String, Object> variableView(Variable v) {
        String value = nullToEmpty(v.valueCipher());
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", v.id());
        view.put("name", nullToEmpty(v.name()));
        view.put("key", nullToEmpty(v.key()));
        view.put("env", nullToEmpty(v.env()));
        view.put("scope", nullToEmpty(v.scope()));
        view.put("value", value);
        view.put("description", nullToEmpty(v.description()));
        view.put("updatedAt", nullToEmpty(v.updatedAt()));
        return view;
    }

    private static String str(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
