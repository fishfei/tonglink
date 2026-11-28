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
import org.springframework.stereotype.Service;

/**
 * AI 设置：负责持久化、厂商预设、密钥掩码与「生效配置」解析。
 *
 * <p>每个厂商在表里独占一行（id 即 provider），接口地址、模型名、API Key 互不干扰；
 * 另用一行 {@code __active__} 记录当前启用的引擎。模型连接信息只来自「设置 → AI 模型」页
 * 保存的数据库配置；未配置（或启用本地规则引擎）时系统保持离线可用，不读取任何 AI 环境变量。</p>
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);
    /** 前端回显掩码时代表「不修改密钥」的占位符。 */
    static final String API_KEY_PLACEHOLDER = "******";
    private static final String LOCAL = "local";
    /** 与仓储保持一致，禁止前端把厂商名写成保留 id。 */
    private static final String RESERVED_ID = "__active__";

    private final AiSettingsRepository repo;
    private final CryptoService crypto;
    private final LlmClient llmClient;

    public AiSettingsService(AiSettingsRepository repo, CryptoService crypto, LlmClient llmClient) {
        this.repo = repo;
        this.crypto = crypto;
        this.llmClient = llmClient;
    }

    /**
     * 当前生效的模型连接参数；{@code enabled=false} 表示应回退本地规则引擎。
     *
     * <p>每次调用都重新读库，保证保存后即时生效，无需重启应用。</p>
     */
    public EffectiveConfig effective() {
        String provider = repo.activeProvider();
        if (LOCAL.equalsIgnoreCase(provider)) {
            return localConfig();
        }
        AiSettings saved = repo.settings(provider);
        if (saved != null && notBlank(saved.endpoint()) && notBlank(saved.apiKeyCipher())) {
            return new EffectiveConfig(true, provider, saved.endpoint(),
                    crypto.decrypt(saved.apiKeyCipher()), saved.model(), "settings");
        }
        return localConfig();
    }

    private EffectiveConfig localConfig() {
        return new EffectiveConfig(false, LOCAL, "", "", "", "local");
    }

    /**
     * 设置页视图：返回每个厂商各自的配置（密钥只回显掩码）、预设与当前生效来源。
     *
     * <p>顶层仍保留当前生效厂商的 provider/endpoint/model/apiKeyMask，方便只看「当前生效」
     * 的调用方；各厂商的完整列表在 {@code configs} 里，切换厂商时前端据此回显各自的密钥。</p>
     */
    public Map<String, Object> view() {
        EffectiveConfig active = effective();

        Map<String, Object> configs = new LinkedHashMap<>();
        for (AiSettings saved : repo.allSettings()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("endpoint", nullToEmpty(saved.endpoint()));
            item.put("model", nullToEmpty(saved.model()));
            item.put("apiKeyMask", apiKeyMask(saved));
            item.put("hasApiKey", notBlank(saved.apiKeyCipher()));
            configs.put(saved.id(), item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", active.provider());
        result.put("endpoint", active.endpoint());
        result.put("model", active.model());
        result.put("apiKeyMask", active.enabled() ? CryptoService.mask(active.apiKey()) : "—");
        result.put("hasApiKey", active.enabled());
        result.put("active", active.enabled());
        result.put("activeProvider", active.provider());
        result.put("activeSource", active.source());
        result.put("activeLabel", activeLabel(active.provider()));
        result.put("configs", configs);
        result.put("presets", presets());
        return result;
    }

    /** 厂商中文名，配置缺失时回退到标识本身。 */
    private String activeLabel(String provider) {
        for (Map<String, Object> preset : presets()) {
            if (provider.equals(preset.get("key"))) {
                return String.valueOf(preset.get("label"));
            }
        }
        return provider;
    }

    /** 计算 API Key 掩码。掩码只用于展示，不代表明文。 */
    private String apiKeyMask(AiSettings saved) {
        String cipher = saved == null ? null : saved.apiKeyCipher();
        return notBlank(cipher) ? CryptoService.mask(crypto.decrypt(cipher)) : "—";
    }

    /**
     * 保存某个厂商的设置并把它设为当前生效引擎。
     *
     * <p>apiKey 为空或掩码占位符时保留「该厂商」原密钥，绝不拿别的厂商的 Key 来补。</p>
     */
    public void save(Map<String, Object> body) {
        String provider = str(body.get("provider")).trim();
        if (provider.isBlank()) {
            provider = LOCAL;
        }
        if (RESERVED_ID.equals(provider)) {
            throw new ApiException(Codes.VALIDATION, "引擎标识非法");
        }
        if (LOCAL.equalsIgnoreCase(provider)) {
            repo.setActiveProvider(LOCAL);
            log.info("AI 引擎已切换为本地规则引擎");
            return;
        }

        String endpoint = str(body.get("endpoint")).trim();
        String model = str(body.get("model")).trim();
        String apiKey = str(body.get("apiKey")).trim();
        if (endpoint.isBlank()) {
            throw new ApiException(Codes.VALIDATION, "请选择模型厂商并填写接口地址");
        }

        AiSettings old = repo.settings(provider);
        if (API_KEY_PLACEHOLDER.equals(apiKey) || apiKey.isEmpty()) {
            apiKey = old == null || old.apiKeyCipher() == null ? "" : crypto.decrypt(old.apiKeyCipher());
        }
        repo.save(new AiSettings(provider, provider, endpoint, crypto.encrypt(apiKey), model, ""));
        repo.setActiveProvider(provider);
        log.info("AI 设置已保存：provider={}, endpoint={}, model={}", provider, endpoint, model);
    }

    /** 恢复为未配置：清空所有厂商配置及生效标记，回退到本地规则引擎。 */
    public void clear() {
        repo.clearAll();
        log.info("AI 设置已清空，回退到本地规则引擎");
    }

    /** 用请求里的临时参数做连接测试，不修改已保存设置。 */
    public Map<String, Object> test(Map<String, Object> body) {
        String provider = str(body.get("provider")).trim();
        if (LOCAL.equalsIgnoreCase(provider)) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "本地规则引擎无需连接外部服务");
            return result;
        }
        String endpoint = str(body.get("endpoint")).trim();
        String model = str(body.get("model")).trim();
        String apiKey = str(body.get("apiKey")).trim();
        if (API_KEY_PLACEHOLDER.equals(apiKey) || apiKey.isEmpty()) {
            AiSettings old = repo.settings(provider);
            apiKey = old == null || old.apiKeyCipher() == null ? "" : crypto.decrypt(old.apiKeyCipher());
        }
        return llmClient.test(endpoint, apiKey, model);
    }

    /** 四家国内主流厂商的 OpenAI 兼容预设：内置地址、默认模型、常用模型列表与官方文档入口。 */
    public List<Map<String, Object>> presets() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(preset("local", "本地规则引擎", "", "", "", List.of(), ""));
        list.add(preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1/chat/completions",
                "deepseek-chat",
                "常用模型，完整列表与下线公告以官方文档为准",
                List.of("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-pro"),
                "https://api-docs.deepseek.com/zh-cn/quick_start/pricing"));
        list.add(preset("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4/chat/completions",
                "glm-4-flash",
                "免费模型 glm-4-flash 可直接体验，新模型请查看官方文档",
                List.of("glm-4-flash", "glm-4-plus", "glm-4.5-air", "glm-4.7", "glm-5.3"),
                "https://docs.bigmodel.cn/cn/guide/start/model-overview"));
        list.add(preset("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
                "qwen-plus",
                "百炼 OpenAI 兼容模式，完整模型列表以官方文档为准",
                List.of("qwen-plus", "qwen-flash", "qwen-max", "qwen3.7-plus", "qwen3.8-max"),
                "https://help.aliyun.com/zh/model-studio/getting-started/models"));
        list.add(preset("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1/chat/completions",
                "hunyuan-turbo",
                "混元 OpenAI 兼容接口，模型编码请按官方文档填写",
                List.of("hunyuan-turbo", "hunyuan-standard", "hunyuan-turbos-latest", "hy4", "hy3"),
                "https://cloud.tencent.com/document/product/1823/130051"));
        list.add(preset("custom", "自定义（OpenAI 兼容）", "", "",
                "填写任意兼容 Chat Completions 的接口，模型名由服务商决定", List.of(), ""));
        return list;
    }

    private Map<String, Object> preset(String key, String label, String endpoint,
                                       String model, String hint, List<String> models, String docsUrl) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", key);
        map.put("label", label);
        map.put("endpoint", endpoint);
        map.put("model", model);
        map.put("hint", hint);
        map.put("models", models);
        map.put("docsUrl", docsUrl);
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
