/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.datasource;

import cn.tonglink.common.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * 协议元数据目录。
 *
 * <p>不同协议需要填写的连接字段不同，前端「新建数据源」抽屉据此动态渲染表单。
 * 数据来自 {@code protocol-fields.json}，新增协议只需改 JSON，无需改代码。</p>
 */
@Component
public class ProtocolCatalog {

    private final Map<String, List<String>> fields;

    public ProtocolCatalog() {
        try (InputStream in = new ClassPathResource("protocol-fields.json").getInputStream()) {
            this.fields = JsonUtil.mapper().readValue(in, new TypeReference<Map<String, List<String>>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("加载协议字段定义失败", e);
        }
    }

    /** 全部协议的字段定义，key 为协议名。 */
    public Map<String, List<String>> all() {
        return fields;
    }

    /** 按关键字模糊匹配协议名，用于筛选下拉候选。 */
    public List<String> match(String keyword) {
        String text = keyword == null ? "" : keyword.toLowerCase();
        return fields.keySet().stream()
                .filter(p -> p.toLowerCase().contains(text))
                .toList();
    }
}
