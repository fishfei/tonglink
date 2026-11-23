/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.common.IdUtil;
import cn.tonglink.common.Result;
import cn.tonglink.model.entity.Credential;
import cn.tonglink.model.entity.Variable;
import cn.tonglink.repository.MetaRepository;
import java.util.ArrayList;
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
 * 凭据与变量接口。
 *
 * <p>两类资源结构相近但语义不同，放在同一控制器便于前端在「变量与凭据」页统一管理。
 * 所有返回值都经过脱敏：只给掩码，绝不返回明文或密文。</p>
 */
@RestController
@RequestMapping("/api/v1")
public class ConfigController {

    private final MetaRepository repo;
    private final CryptoService crypto;

    public ConfigController(MetaRepository repo, CryptoService crypto) {
        this.repo = repo;
        this.crypto = crypto;
    }

    // ---------------- 凭据 ----------------

    /** 凭据列表（只含掩码）。 */
    @GetMapping("/credentials")
    public Result<List<Map<String, Object>>> credentials() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Credential c : repo.credentials()) {
            result.add(credentialView(c));
        }
        return Result.ok(result);
    }

    /** 新建凭据；{@code id} 可由前端指定（导入场景），否则自动生成。 */
    @PostMapping("/credentials")
    public Result<Map<String, Object>> createCredential(@RequestBody Map<String, Object> body) {
        String value = str(body.get("value"), "");
        String now = repo.now();
        Credential c = new Credential(
                str(body.get("id"), IdUtil.prefix("C")),
                str(body.get("name"), "未命名凭据"),
                str(body.get("type"), "API Key"),
                str(body.get("scope"), "prod"),
                crypto.encrypt(value),
                CryptoService.mask(value),
                str(body.get("description"), ""),
                now,
                now);
        repo.saveCredential(c);
        return Result.ok(credentialView(c));
    }

    /**
     * 更新凭据。
     *
     * <p>请求体 {@code value} 留空表示「不修改原值」，避免前端在只改名字时
     * 把加密值覆盖成空串。</p>
     */
    @PutMapping("/credentials/{id}")
    public Result<Map<String, Object>> updateCredential(@PathVariable String id,
                                                        @RequestBody Map<String, Object> body) {
        Credential old = repo.credential(id);
        if (old == null) {
            throw new ApiException(Codes.NOT_FOUND, "凭据不存在");
        }
        String raw = str(body.get("value"), "");
        String value = raw.isBlank() ? crypto.decrypt(old.valueCipher()) : raw;
        Credential c = new Credential(id,
                str(body.get("name"), old.name()),
                str(body.get("type"), old.type()),
                str(body.get("scope"), old.scope()),
                crypto.encrypt(value),
                CryptoService.mask(value),
                str(body.get("description"), nullToEmpty(old.description())),
                old.createdAt(),
                repo.now());
        repo.updateCredential(c);
        return Result.ok(credentialView(c));
    }

    /** 删除凭据。 */
    @DeleteMapping("/credentials/{id}")
    public Result<Void> deleteCredential(@PathVariable String id) {
        repo.deleteCredential(id);
        return Result.ok();
    }

    // ---------------- 变量 ----------------

    /** 变量列表（只含掩码）。 */
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
        String value = str(body.get("value"), "");
        String now = repo.now();
        Variable v = new Variable(
                str(body.get("id"), IdUtil.prefix("V")),
                str(body.get("name"), ""),
                str(body.get("key"), ""),
                str(body.get("env"), "prod"),
                crypto.encrypt(value),
                str(body.get("scope"), "global"),
                str(body.get("description"), ""),
                now,
                now);
        repo.saveVariable(v);
        return Result.ok(variableView(v));
    }

    /** 更新变量；{@code value} 留空同样表示不修改原值。 */
    @PutMapping("/variables/{id}")
    public Result<Map<String, Object>> updateVariable(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        Variable old = repo.variable(id);
        if (old == null) {
            throw new ApiException(Codes.NOT_FOUND, "变量不存在");
        }
        String raw = str(body.get("value"), "");
        String value = raw.isBlank() ? crypto.decrypt(old.valueCipher()) : raw;
        Variable v = new Variable(id,
                str(body.get("name"), old.name()),
                str(body.get("key"), old.key()),
                str(body.get("env"), old.env()),
                crypto.encrypt(value),
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

    // ---------------- 视图与工具 ----------------

    /** 凭据视图：密文不出库，只给掩码。 */
    private Map<String, Object> credentialView(Credential c) {
        java.util.LinkedHashMap<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", c.id());
        view.put("name", c.name());
        view.put("type", nullToEmpty(c.type()));
        view.put("scope", nullToEmpty(c.scope()));
        view.put("mask", c.mask() == null ? "******" : c.mask());
        view.put("description", nullToEmpty(c.description()));
        view.put("updatedAt", nullToEmpty(c.updatedAt()));
        return view;
    }

    /**
     * 变量视图。
     *
     * <p>掩码需要在解密后再打，否则前端会看到 {@code v1:abc******} 这样的密文前缀。</p>
     */
    private Map<String, Object> variableView(Variable v) {
        String mask;
        try {
            mask = CryptoService.mask(crypto.decrypt(v.valueCipher()));
        } catch (Exception e) {
            mask = "******";
        }
        java.util.LinkedHashMap<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("id", v.id());
        view.put("name", nullToEmpty(v.name()));
        view.put("key", nullToEmpty(v.key()));
        view.put("env", nullToEmpty(v.env()));
        view.put("scope", nullToEmpty(v.scope()));
        view.put("mask", mask);
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
