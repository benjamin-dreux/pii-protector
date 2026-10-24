package com.example.piiprotector.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PiiEncryptionServiceTest {

    private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    private final PiiEncryptionService encryptionService = new PiiEncryptionService(TEST_KEY);

    @Test
    void encryptsWithFf1WithoutChangingFormat() {
        String encrypted = encryptionService.encrypt("alice@example.org", "pii.email");

        assertThat(encrypted).hasSize("alice@example.org".length())
                .isNotEqualTo("alice@example.org")
                .doesNotContain("v1:")
                .matches("[A-Za-z0-9]+@[A-Za-z0-9]+\\.[A-Za-z0-9]+");
    }

    @Test
    void encryptsSamePiiDeterministically() {
        String first = encryptionService.encrypt("alice@example.org", "pii.email");
        String second = encryptionService.encrypt("alice@example.org", "pii.email");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void decryptsFf1EncryptedValue() {
        String encrypted = encryptionService.encrypt("alice@example.org", "pii.email");

        assertThat(encryptionService.decrypt(encrypted, "pii.email")).isEqualTo("alice@example.org");
    }

    @Test
    void rejectsLegacyAesGcmValues() {
        assertThatThrownBy(() -> encryptionService.decrypt("v1:legacy-ciphertext", "pii.email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Legacy AES-GCM values are not supported");
    }

    @Test
    void usesAlphabetAndLeavesSeparatorsUnchanged() {
        String plaintext = "Alice Example 1985-04-12";
        String encrypted = encryptionService.encrypt(plaintext, "pii.name");

        assertThat(encrypted).hasSize(plaintext.length())
                .doesNotContain("v1:")
                .matches("[A-Za-z0-9 -]+");
        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(encrypted.charAt(5)).isEqualTo(' ');
        assertThat(encrypted.charAt(13)).isEqualTo(' ');
        assertThat(encrypted.substring(18, 19)).isEqualTo("-");
        assertThat(encryptionService.encrypt(plaintext, "pii.other")).isEqualTo(encrypted);
        assertThat(encryptionService.decrypt(encrypted, "pii.name")).isEqualTo(plaintext);
    }

    @Test
    void leavesSingleCharacterRunsUnchanged() {
        assertThat(encryptionService.encrypt("A B 1", "pii.short")).isEqualTo("A B 1");
        assertThat(encryptionService.encrypt("123", "public.contact_phones.extension")).isEqualTo("123");
    }

    @Test
    void rejectsKeyWithWrongLength() {
        assertThatThrownBy(() -> new PiiEncryptionService("AA=="))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
