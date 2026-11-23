/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.common;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * 有序 ID 生成器。
 *
 * <p>ID 前 10 位由毫秒时间戳按 Base32 编码，后 16 位为安全随机字符，
 * 因此同一毫秒内仍高概率唯一，且按字典序大致等于按创建时间排序，
 * 前端列表可以直接按 ID 排序而不必依赖数据库自增。</p>
 */
public final class IdUtil {

    /** 去掉易混淆字符（I/L/O/U）的 32 字符表。 */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 时间部分长度（毫秒 64 位按 5 位一组，约 10 组足够表达）。 */
    private static final int TIME_PART_LENGTH = 10;

    /** 随机部分长度（16 字节的低 5 位）。 */
    private static final int RANDOM_PART_LENGTH = 16;

    private IdUtil() {
    }

    /**
     * 生成 26 位 ID。
     *
     * @return 可直接作为主键的字符串
     */
    public static String next() {
        long time = Instant.now().toEpochMilli();
        StringBuilder sb = new StringBuilder(TIME_PART_LENGTH + RANDOM_PART_LENGTH);
        long value = time;
        for (int i = TIME_PART_LENGTH - 1; i >= 0; i--) {
            sb.insert(0, ALPHABET[(int) (value & 0x1F)]);
            value >>>= 5;
        }
        byte[] bytes = new byte[RANDOM_PART_LENGTH];
        RANDOM.nextBytes(bytes);
        for (byte b : bytes) {
            sb.append(ALPHABET[b & 0x1F]);
        }
        return sb.toString();
    }

    /**
     * 生成带业务前缀的 ID，便于日志与接口排查。
     *
     * @param prefix 业务前缀，如 {@code FLOW}、{@code DS}、{@code EXEC}
     * @return 形如 {@code FLOW-1ABCD2345EFGH6789}
     */
    public static String prefix(String prefix) {
        return prefix + "-" + next();
    }
}
