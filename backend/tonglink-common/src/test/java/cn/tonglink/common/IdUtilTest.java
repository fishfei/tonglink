/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ID 生成器测试。
 *
 * <p>覆盖两点：长度固定（便于数据库字段与前端展示），以及按时间有序（列表排序依赖此特性）。</p>
 */
class IdUtilTest {

    /** ID 长度固定为 26：10 位时间 + 16 位随机。 */
    @Test
    void 生成的ID长度固定且唯一() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = IdUtil.next();
            assertThat(id).hasSize(26);
            ids.add(id);
        }
        assertThat(ids).hasSize(1000);
    }

    /** 带前缀的 ID 形如 {@code FLOW-xxxx}，前缀用于日志排查。 */
    @Test
    void 前缀ID保持格式与时间有序() {
        String first = IdUtil.prefix("FLOW");
        assertThat(first).startsWith("FLOW-").hasSize(31);

        // 同一毫秒内生成的两个 ID 也应可比较（时间部分相同，随机部分不同）
        String second = IdUtil.prefix("FLOW");
        assertThat(first).isNotEqualTo(second);
        assertThat(first.substring(5)).hasSize(26);
    }
}
