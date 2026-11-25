/*
 * SPDX-License-Identifier: MIT
 * Copyright (c) 2026 TongLink Studio Contributors
 */
package cn.tonglink.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 凭据加解密服务。
 *
 * <p>采用 AES-256-GCM：主密钥由配置口令经 SHA-256 派生，每条密文自带 12 字节随机 IV，
 * 因此相同明文每次加密结果不同，避免通过密文比对推断出相同凭据。
 * 密文格式 {@code v1:base64(iv + ciphertext + tag)}，前缀用于将来平滑换算法。</p>
 *
 * <p>生产环境务必通过 {@code TONGLINK_CRYPTO_KEY} 覆盖默认口令；更换口令会导致
 * 已有密文无法解密，需要重新录入凭据。</p>
 */
@Service
public class CryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "v1:";

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;

    public CryptoService(@Value("${tonglink.crypto.key:tonglink-studio-dev-key-change-me}") String passphrase) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    /**
     * 加密明文。
     *
     * @param plain 明文，空值直接返回空串（表示「未设置」而非「空密码」）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /**
     * 解密密文；对没有前缀的历史数据按明文原样返回，保证老数据可用。
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return "";
        }
        if (!cipherText.startsWith(PREFIX)) {
            return cipherText;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(cipherText.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH);
            byte[] encrypted = new byte[payload.length - IV_LENGTH];
            System.arraycopy(payload, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败，请检查 TONGLINK_CRYPTO_KEY 是否变更", e);
        }
    }

    /**
     * 生成展示用掩码：保留首尾各 3 位，中间固定 6 个星号。
     *
     * <p>长度不超过 6 的值整体掩码，避免短密码被反推出大部分字符。</p>
     */
    public static String mask(String value) {
        if (value == null || value.isEmpty()) {
            return "—";
        }
        if (value.length() <= 6) {
            return "******";
        }
        return value.substring(0, 3) + "******" + value.substring(value.length() - 3);
    }
}
