package com.sparrow.common.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesGcmCryptoTest {

    /** 生成 32 字节主密钥(AES-256)。 */
    private static byte[] key(String seed) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(seed.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void encryptThenDecryptRoundTrip() throws Exception {
        byte[] masterKey = key("sparrow-secret");
        String plain = "sk-abc1234567890xyz";
        String cipher = AesGcmCrypto.encrypt(plain, masterKey);

        assertThat(cipher).isNotEqualTo(plain);
        assertThat(cipher).doesNotContain(plain);
        assertThat(AesGcmCrypto.decrypt(cipher, masterKey)).isEqualTo(plain);
    }

    /** 相同明文每次加密产生不同密文(随机 IV)。 */
    @Test
    void encryptIsNonDeterministic() throws Exception {
        byte[] masterKey = key("sparrow-secret");
        String plain = "sk-same-value";
        String c1 = AesGcmCrypto.encrypt(plain, masterKey);
        String c2 = AesGcmCrypto.encrypt(plain, masterKey);
        assertThat(c1).isNotEqualTo(c2);
        assertThat(AesGcmCrypto.decrypt(c1, masterKey)).isEqualTo(plain);
        assertThat(AesGcmCrypto.decrypt(c2, masterKey)).isEqualTo(plain);
    }

    /** 错误主密钥解密失败(抛异常,不返回乱码)。 */
    @Test
    void decryptWithWrongKeyFails() throws Exception {
        String cipher = AesGcmCrypto.encrypt("secret", key("correct"));
        assertThatThrownBy(() -> AesGcmCrypto.decrypt(cipher, key("wrong")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("解密失败");
    }

    /** null/空明文安全处理。 */
    @Test
    void handlesNullAndBlank() throws Exception {
        byte[] masterKey = key("sparrow-secret");
        assertThat(AesGcmCrypto.encrypt(null, masterKey)).isNull();
        assertThat(AesGcmCrypto.decrypt(null, masterKey)).isNull();
        assertThat(AesGcmCrypto.decrypt("", masterKey)).isNull();
    }

    /** 支持中文等多字节内容。 */
    @Test
    void supportsUnicode() throws Exception {
        byte[] masterKey = key("sparrow-secret");
        String plain = "你好·Sparrow🔑";
        String cipher = AesGcmCrypto.encrypt(plain, masterKey);
        assertThat(AesGcmCrypto.decrypt(cipher, masterKey)).isEqualTo(plain);
    }

    /** base64 密钥也兼容(32 字节)。 */
    @Test
    void supportsBase64Key() throws Exception {
        byte[] masterKey = key("sparrow-secret");
        String cipher = AesGcmCrypto.encrypt("sk-token", masterKey);
        // 模拟配置注入的 base64 主密钥解码后再用
        byte[] decoded = Base64.getDecoder().decode(Base64.getEncoder().encodeToString(masterKey));
        assertThat(AesGcmCrypto.decrypt(cipher, decoded)).isEqualTo("sk-token");
    }
}
