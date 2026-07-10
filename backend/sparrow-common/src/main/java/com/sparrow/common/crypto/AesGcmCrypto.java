package com.sparrow.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 对称加解密工具：用于在数据库中安全存储敏感配置(如模型 API Key)。
 *
 * <p>密文格式为 base64(IV(12B) || ciphertext+tag)，解密时按固定偏移拆分。
 * 主密钥由调用方从服务器环境变量注入({@code SPARROW_MODEL_CONFIG_SECRET})，
 * 本工具自身不持有任何密钥。IV 每次加密随机生成，保证相同明文产生不同密文。
 */
public final class AesGcmCrypto {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private AesGcmCrypto() {
    }

    /** 加密明文，返回 base64(IV || ciphertext+tag)。 */
    public static String encrypt(String plain, byte[] masterKey) {
        if (plain == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密 {@link #encrypt} 产生的 base64 密文，返回明文。 */
    public static String decrypt(String cipherBase64, byte[] masterKey) {
        if (cipherBase64 == null || cipherBase64.isBlank()) {
            return null;
        }
        try {
            byte[] all = Base64.getDecoder().decode(cipherBase64);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度非法");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] cipherText = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(masterKey, "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM 解密失败(主密钥不匹配或密文损坏): " + e.getMessage(), e);
        }
    }
}
