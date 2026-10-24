package com.example.piiprotector.crypto;

import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.fpe.FPEFF1Engine;
import org.bouncycastle.crypto.params.FPEParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class PiiEncryptionService {

    private static final String ALPHANUMERIC_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String DIGIT_ALPHABET = "0123456789";
    private static final int KEY_LENGTH_BYTES = 32;

    private final FPEFF1Engine alphanumericEncryptEngine;
    private final FPEFF1Engine alphanumericDecryptEngine;
    private final FPEFF1Engine numericEncryptEngine;
    private final FPEFF1Engine numericDecryptEngine;

    public PiiEncryptionService(@Value("${pii.encryption.key:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            throw new IllegalStateException(
                    "Set pii.encryption.key in application.properties or PII_ENCRYPTION_KEY to a Base64-encoded 256-bit key"
            );
        }

        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("pii.encryption.key must be valid Base64", exception);
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException("pii.encryption.key must decode to exactly 32 bytes");
        }
        alphanumericEncryptEngine = createEngine(keyBytes, ALPHANUMERIC_ALPHABET, true);
        alphanumericDecryptEngine = createEngine(keyBytes, ALPHANUMERIC_ALPHABET, false);
        numericEncryptEngine = createEngine(keyBytes, DIGIT_ALPHABET, true);
        numericDecryptEngine = createEngine(keyBytes, DIGIT_ALPHABET, false);
    }

    public String encrypt(String plaintext, String purpose) {
        return transformFf1(plaintext, purpose, true);
    }

    public String decrypt(String encryptedValue, String purpose) {
        if (encryptedValue.startsWith("v1:")) {
            throw new IllegalArgumentException("Legacy AES-GCM values are not supported");
        }
        return transformFf1(encryptedValue, purpose, false);
    }

    private String transformFf1(String value, String purpose, boolean encrypt) {
        StringBuilder result = new StringBuilder(value);
        boolean numericAlphabet = usesNumericAlphabet(purpose);
        String alphabet = numericAlphabet ? DIGIT_ALPHABET : ALPHANUMERIC_ALPHABET;
        List<Integer> positions = new ArrayList<>();
        for (int index = 0; index < value.length(); index++) {
            if (alphabet.indexOf(value.charAt(index)) >= 0) {
                positions.add(index);
            }
        }
        int minimumLength = (int) Math.ceil(Math.log(1_000_000) / Math.log(alphabet.length()));
        if (positions.size() >= minimumLength) {
            transformSegment(result, value, positions, alphabet, numericAlphabet, encrypt);
        }
        return result.toString();
    }

    private void transformSegment(
            StringBuilder result,
            String value,
            List<Integer> positions,
            String alphabet,
            boolean numericAlphabet,
            boolean encrypt
    ) {
        byte[] input = new byte[positions.size()];
        for (int index = 0; index < positions.size(); index++) {
            int digit = alphabet.indexOf(value.charAt(positions.get(index)));
            if (digit < 0) {
                throw new IllegalArgumentException("Value contains a character outside its FF1 alphabet");
            }
            input[index] = (byte) digit;
        }

        byte[] output = new byte[input.length];
        FPEFF1Engine engine = engine(numericAlphabet, encrypt);
        synchronized (engine) {
            engine.processBlock(input, 0, input.length, output, 0);
        }

        for (int index = 0; index < positions.size(); index++) {
            result.setCharAt(positions.get(index), alphabet.charAt(output[index] & 0xff));
        }
    }

    private boolean usesNumericAlphabet(String purpose) {
        String column = purpose.substring(purpose.lastIndexOf('.') + 1);
        return column.equals("date_of_birth") || column.equals("phone_number") || column.equals("extension");
    }

    private FPEFF1Engine engine(boolean numericAlphabet, boolean encrypt) {
        if (numericAlphabet) {
            return encrypt ? numericEncryptEngine : numericDecryptEngine;
        }
        return encrypt ? alphanumericEncryptEngine : alphanumericDecryptEngine;
    }

    private FPEFF1Engine createEngine(byte[] keyBytes, String alphabet, boolean encrypt) {
        BlockCipher aes = AESEngine.newInstance();
        FPEFF1Engine engine = new FPEFF1Engine(aes);
        engine.init(encrypt, new FPEParameters(
                new KeyParameter(keyBytes),
                alphabet.length(),
                new byte[0]
        ));
        return engine;
    }

}
