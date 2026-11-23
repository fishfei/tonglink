/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

/**
 * JSON 序列化工具。
 *
 * <p>流程定义、节点配置、执行输入输出都以 JSON 字符串存库，业务层需要在
 * {@code Map}、JSON 文本与强类型对象之间来回转换，这里统一收口，避免每个模块各建一个 ObjectMapper。</p>
 */
public final class JsonUtil {

    /** 全局共享实例：忽略未知字段，便于前后端字段增删时保持兼容。 */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private JsonUtil() {
    }

    /**
     * 序列化为 JSON 文本。
     *
     * @param value 任意对象，可为 null
     * @return JSON 字符串
     * @throws ApiException 序列化失败时抛出，通常意味着对象存在循环引用
     */
    public static String stringify(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new ApiException("JSON 序列化失败：" + e.getMessage());
        }
    }

    /**
     * 反序列化为指定类型。
     *
     * @param json JSON 文本，空串返回 null
     * @param type 目标类型
     * @return 对象实例，入参为空时为 null
     */
    public static <T> T parse(String json, Class<T> type) {
        try {
            return json == null || json.isBlank() ? null : MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ApiException("JSON 解析失败：" + e.getMessage());
        }
    }

    /** 反序列化为泛型类型，例如 {@code Map<String, Object>}。 */
    public static <T> T parse(String json, TypeReference<T> type) {
        try {
            return json == null || json.isBlank() ? null : MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new ApiException("JSON 解析失败：" + e.getMessage());
        }
    }

    /**
     * 把任意对象转成 Map。
     *
     * <p>用于处理请求体中既可能是对象、也可能是字符串的配置字段。</p>
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        Map<String, Object> parsed = parse(stringify(value), new TypeReference<Map<String, Object>>() {
        });
        return parsed == null ? Map.of() : parsed;
    }

    /** 把 JSON 文本解析成树模型，便于读取宽松结构的 LLM 响应。 */
    public static JsonNode tree(String json) {
        return parse(json, JsonNode.class);
    }

    /** 暴露底层 Mapper，供需要流式读写的场景使用（如加载 classpath 资源）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
