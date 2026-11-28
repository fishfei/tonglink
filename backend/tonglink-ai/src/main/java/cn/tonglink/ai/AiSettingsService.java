/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.ai;

import cn.tonglink.common.ApiException;
import cn.tonglink.common.Codes;
import cn.tonglink.model.entity.AiSettings;
import cn.tonglink.repository.AiSettingsRepository;
import cn.tonglink.security.CryptoService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AI 设置：负责持久化、厂商预设、密钥掩码与「生效配置」解析。
 *
 * <p>生效优先级：数据库设置（provider≠local 且地址/Key 齐全）→ 环境变量
 * {@code tonglink.ai.*} → 本地规则引擎。这样既支持运行时改配置免重启，也保留
 * 只用环境变量部署的老方式；两者都没有时系统保持离线可用。</p>
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);
    private static final String DEFAULT_ID = "default";
    /** 前端回显掩码时代表「不修改密钥」的占位符。 */
    static final String API_KEY_PLACEHOLDER = "******";

    private final AiSettingsRepository repo;
    private final CryptoService crypto;
    private final LlmClient llmClient;
    private final String envProvider;
    private final String envEndpoint;
    private final String envApiKey;
    private final String envModel;

    public AiSettingsService(AiSettingsRepository repo, CryptoService crypto, LlmClient llmClient,
                             @Value("${tonglink.ai.provider:local}") String envProvider,
                             @Value("${tonglink.ai.endpoint:}") String envEndpoint,
                             @Value("${tonglink.ai.api-key:}") String envApiKey,
                             @Value("${tonglink.ai.model:gpt-4o-mini}") String envModel) {
        this.repo = repo;
        this.crypto = crypto;
        this.llmClient = llmClient;
        this.envProvider = envProvider;
        this.envEndpoint = envEndpoint;
        this.envApiKey = envApiKey;
        this.envModel = envModel;
    }

    /**
     * 当前生效的模型连接参数；{@code enabled=false} 表示应回退本地规则引擎。
     *
     * <p>每次调用都重新读库，保证保存后即时生效，无需重启应用。</p>
     */
    public EffectiveConfig effective() {
        AiSettings saved = repo.settings();
        if (saved != null && !"local".equalsIgnoreCase(saved.provider())
                && notBlank(saved.endpoint()) && notBlank(saved.apiKeyCipher())) {
            return new EffectiveConfig(true, saved.provider(), saved.endpoint(),
                    crypto.decrypt(saved.apiKeyCipher()), saved.model(), "settings");
        }
        if (!"local".equalsIgnoreCase(envProvider) && notBlank(envEndpoint)) {
            return new EffectiveConfig(true, envProvider, envEndpoint, envApiKey, envModel, "environment");
        }
        return new EffectiveConfig(false, "local", "", "", "", "local");
    }

    /** 设置页视图：密钥以掩码返回，附带厂商预设与当前生效来源。 */
    public Map<String, Object> view() {
        AiSettings saved = repo.settings();
        EffectiveConfig active = effective();
        String provider = saved == null ? "local" : saved.provider();
        String endpoint = saved == null ? "" : nullToEmpty(saved.endpoint());
        String model = saved == null ? "" : nullToEmpty(saved.model());

        // 未保存过设置但环境变量里配了模型时，回显环境变量，避免用户误以为没配置
        if (saved == null && active.enabled()) {
            provider = active.provider();
            endpoint = active.endpoint();
            model = active.model() == null ? "" : active.model();
        }
        String keyMask = ApiSettingsKeyMask(saved, active);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("endpoint", endpoint);
        result.put("model", model);
        result.put("apiKeyMask", keyMask);
        boolean savedKey = saved != null && notBlank(saved.apiKeyCipher());
        result.put("hasApiKey", !"local".equalsIgnoreCase(provider) && (savedKey || active.enabled()));
        result.put("active", active.enabled());
        result.put("activeProvider", active.provider());
        result.put("activeSource", active.source());
        result.put("presets", presets());
        return result;
    }

    /** 计算 API Key 掩码。掩码只用于展示，不代表明文。 */
    private String ApiSettingsKeyMask(AiSettings saved, EffectiveConfig active) {
        String cipher = saved == null ? null : saved.apiKeyCipher();
        if (notBlank(cipher)) {
            return CryptoService.mask(crypto.decrypt(cipher));
        }
        if (active.enabled() && notBlank(active.apiKey())) {
            return CryptoService.mask(active.apiKey());
        }
        return "—";
    }

    /** 保存设置。apiKey 为空或掩码占位符时保留原密钥（不清除已有配置）。 */
    public void save(Map<String, Object> body) {
        String provider = str(body.get("provider"));
        if (provider.isBlank()) {
            provider = "local";
        }
        String endpoint = str(body.get("endpoint")).trim();
        String model = str(body.get("model")).trim();
        String apiKey = str(body.get("apiKey")).trim();

        AiSettings old = repo.settings();
        if (!"local".equalsIgnoreCase(provider) && endpoint.isBlank()) {
            throw new ApiException(Codes.VALIDATION, "请选择模型厂商并填写接口地址");
        }
        if (API_KEY_PLACEHOLDER.equals(apiKey) || apiKey.isEmpty()) {
            // 保留原密钥（新配置且未提供 Key 时为空，连接测试会明确失败）
            apiKey = old == null || old.apiKeyCipher() == null ? "" : crypto.decrypt(old.apiKeyCipher());
        }
        String cipher = crypto.encrypt(apiKey);
        repo.save(new AiSettings(DEFAULT_ID, provider, endpoint, cipher, model, ""));
        log.info("AI 设置已保存：provider={}, endpoint={}, model={}", provider, endpoint, model);
    }

    /** 恢复为未配置：删除设置行，回退到环境变量或本地规则引擎。 */
    public void clear() {
        repo.clear();
        log.info("AI 设置已清空，回退到环境变量 / 本地规则引擎");
    }

    /** 用请求里的临时参数做连接测试，不修改已保存设置。 */
    public Map<String, Object> test(Map<String, Object> body) {
        String provider = str(body.get("provider"));
        if ("local".equalsIgnoreCase(provider)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "本地规则引擎无需连接外部服务");
            return result;
        }
        String endpoint = str(body.get("endpoint")).trim();
        String model = str(body.get("model")).trim();
        String apiKey = str(body.get("apiKey")).trim();
        if (API_KEY_PLACEHOLDER.equals(apiKey) || apiKey.isEmpty()) {
            AiSettings old = repo.settings();
            apiKey = old == null || old.apiKeyCipher() == null ? "" : crypto.decrypt(old.apiKeyCipher());
        }
        return llmClient.test(endpoint, apiKey, model);
    }

    /** 四家国内主流厂商的 OpenAI 兼容预设，前端选择后自动填充地址与模型名。 */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(preset("local", "本地规则引擎", "", "", "离线可用，无需 API Key"));
        list.add(preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1/chat/completions",
                "deepseek-chat", "deepseek-chat / deepseek-reasoner"));
        list.add(preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "glm-4-flash", "glm-4-flash / glm-4-plus / glm-4.5"));
        list.add(preset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "qwen-plus", "qwen-plus / qwen-turbo / qwen-max"));
        list.add(preset("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
                "hunyuan-turbo", "hunyuan-turbo / hunyuan-standard"));
        list.add(preset("custom", "自定义（OpenAI 兼容）", "", "", "填写任意兼容 Chat Completions 的接口"));
        return list;
    }

    private Map<String, Object> preset(String key, String label, String endpoint,
                                       String model, String hint) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("endpoint", endpoint);
        map.put("model", model);
        map.put("hint", hint);
        return map;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 生效配置快照。 */
    public record EffectiveConfig(boolean enabled, String provider, String endpoint,
                                  String apiKey, String model, String source) {
    }
}
