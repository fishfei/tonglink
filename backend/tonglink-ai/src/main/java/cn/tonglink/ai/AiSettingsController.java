/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.Result;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 设置接口：读写大模型连接配置、连接测试与恢复默认。
 *
 * <p>API Key 只以掩码返回，前端提交掩码占位符表示「不修改密钥」。</p>
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiSettingsController {

    private final AiSettingsService service;

    public AiSettingsController(AiSettingsService service) {
        this.service = service;
    }

    /** 当前设置 + 厂商预设 + 生效状态。 */
    @GetMapping("/settings")
    public Result<Map<String, Object>> settings() {
        return Result.ok(service.view());
    }

    /** 保存设置；保存后立即生效，无需重启。 */
    @PutMapping("/settings")
    public Result<Void> save(@RequestBody Map<String, Object> body) {
        service.save(body);
        return Result.ok();
    }

    /** 用草稿参数测试连接，不修改已保存设置。 */
    @PostMapping("/settings/test")
    public Result<Map<String, Object>> test(@RequestBody Map<String, Object> body) {
        return Result.ok(service.test(body));
    }

    /** 清空设置，回退到环境变量 / 本地规则引擎。 */
    @PostMapping("/settings/reset")
    public Result<Void> reset() {
        service.clear();
        return Result.ok();
    }
}
