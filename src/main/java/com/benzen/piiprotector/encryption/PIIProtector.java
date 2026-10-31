package com.benzen.piiprotector.encryption;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import org.bouncycastle.crypto.fpe.FPEFF1Engine;
import org.bouncycastle.crypto.params.FPEParameters;
import org.bouncycastle.crypto.params.KeyParameter;

public final class PIIProtector {
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private final Map<PIITag, FPEFF1Engine> engines = new EnumMap<>(PIITag.class);

    public PIIProtector(byte[] encryptionKey) {
        Objects.requireNonNull(encryptionKey, "encryptionKey");
        if (encryptionKey.length != 32) {
            throw new IllegalArgumentException("The encryption key must contain exactly 32 bytes");
        }

        for (PIITag tag : PIITag.values()) {
            var engine = new FPEFF1Engine();
            engine.init(true, new FPEParameters(
                    new KeyParameter(encryptionKey.clone()),
                    tag.radix(),
                    tag.name().getBytes(StandardCharsets.UTF_8)));
            engines.put(tag, engine);
        }
    }

    public String encrypt(PIITag tag, String value) {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(value, "value");

        if (tag == PIITag.DATE) {
            return encryptDate(value);
        }
        if (value.length() < tag.minimumLength() || value.length() > tag.maximumLength()) {
            throw new IllegalArgumentException("The value length is outside the range supported by the tag");
        }
        if (tag.hasLuhnCheckDigit() && !isLuhnValid(value)
                || tag.hasVinCheckDigit() && !isVinCheckDigitValid(value)) {
            throw new IllegalArgumentException("The value must satisfy the required checksum");
        }

        var valueToEncrypt = valueWithoutCheckDigit(tag, value);
        var bytes = new byte[valueToEncrypt.length()];
        for (var i = 0; i < valueToEncrypt.length(); i++) {
            bytes[i] = tag.encode(valueToEncrypt.charAt(i));
        }

        var encryptedBytes = new byte[bytes.length];
        var engine = engines.get(tag);
        synchronized (engine) {
            engine.processBlock(bytes, 0, bytes.length, encryptedBytes, 0);
        }

        var encryptedValue = new StringBuilder(encryptedBytes.length);
        for (var encryptedByte : encryptedBytes) {
            encryptedValue.append(tag.decode(encryptedByte));
        }
        if (tag.hasLuhnCheckDigit()) {
            encryptedValue.append(calculateLuhnCheckDigit(encryptedValue));
        } else if (tag.hasVinCheckDigit()) {
            encryptedValue.insert(8, calculateVinCheckDigit(encryptedValue));
        }
        return encryptedValue.toString();
    }

    private static String valueWithoutCheckDigit(PIITag tag, String value) {
        if (tag.hasLuhnCheckDigit()) {
            return value.substring(0, value.length() - 1);
        }
        if (tag.hasVinCheckDigit()) {
            return value.substring(0, 8) + value.substring(9);
        }
        return value;
    }

    private static boolean isVinCheckDigitValid(String vin) {
        return vin.charAt(8) == calculateVinCheckDigit(vin);
    }

    private static char calculateVinCheckDigit(CharSequence vinWithoutCheckDigit) {
        var weights = new int[] {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        var weightedSum = 0;
        for (var index = 0; index < weights.length; index++) {
            if (index == 8) {
                continue;
            }
            var characterIndex = vinWithoutCheckDigit.length() == 16 && index > 8 ? index - 1 : index;
            weightedSum += vinCharacterValue(vinWithoutCheckDigit.charAt(characterIndex)) * weights[index];
        }
        var remainder = weightedSum % 11;
        return remainder == 10 ? 'X' : (char) ('0' + remainder);
    }

    private static int vinCharacterValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        return switch (character) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> throw new IllegalArgumentException("The value contains a character outside the tag's alphabet");
        };
    }

    private String encryptDate(String value) {
        var date = parseDate(value);
        var dateDigits = DATE_FORMAT.format(date).replace("-", "");
        var tag = PIITag.DATE;
        var current = new byte[dateDigits.length()];
        for (var i = 0; i < dateDigits.length(); i++) {
            current[i] = tag.encode(dateDigits.charAt(i));
        }

        var next = new byte[current.length];
        var engine = engines.get(tag);
        synchronized (engine) {
            do {
                engine.processBlock(current, 0, current.length, next, 0);
                var previous = current;
                current = next;
                next = previous;
                dateDigits = decode(tag, current);
            } while (!isValidDateDigits(dateDigits));
        }

        return dateDigits.substring(0, 4) + "-"
                + dateDigits.substring(4, 6) + "-"
                + dateDigits.substring(6, 8);
    }

    private static LocalDate parseDate(String value) {
        if (value.length() != PIITag.DATE.minimumLength()
                || value.length() != PIITag.DATE.maximumLength()) {
            throw new IllegalArgumentException("The value must be a valid date in yyyy-MM-dd format");
        }

        try {
            var date = LocalDate.parse(value, DATE_FORMAT);
            if (date.getYear() < 1) {
                throw new IllegalArgumentException("The value must be a valid date in yyyy-MM-dd format");
            }
            return date;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("The value must be a valid date in yyyy-MM-dd format", exception);
        }
    }

    private static boolean isValidDateDigits(String digits) {
        var formattedDate = digits.substring(0, 4) + "-"
                + digits.substring(4, 6) + "-"
                + digits.substring(6, 8);
        try {
            return LocalDate.parse(formattedDate, DATE_FORMAT).getYear() >= 1;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static String decode(PIITag tag, byte[] symbols) {
        var value = new StringBuilder(symbols.length);
        for (var symbol : symbols) {
            value.append(tag.decode(symbol));
        }
        return value.toString();
    }

    private static boolean isLuhnValid(String value) {
        var sum = 0;
        var doubleDigit = true;
        for (var i = value.length() - 2; i >= 0; i--) {
            var digit = value.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return (sum + value.charAt(value.length() - 1) - '0') % 10 == 0;
    }

    private static char calculateLuhnCheckDigit(CharSequence valueWithoutCheckDigit) {
        var sum = 0;
        var doubleDigit = true;
        for (var i = valueWithoutCheckDigit.length() - 1; i >= 0; i--) {
            var digit = valueWithoutCheckDigit.charAt(i) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return (char) ('0' + (10 - sum % 10) % 10);
    }
}
