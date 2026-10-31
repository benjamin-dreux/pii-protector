package com.benzen.piiprotector.encryption;

import java.util.List;

public enum PIITag {
    EMAIL(3, 254,
            new CharacterRange(' ', '~'),
            new CharacterRange('\u00A0', '\u00FF')),
    TEXT(3, 1024,
            new CharacterRange(' ', '~'),
            new CharacterRange('\u00A0', '\u00FF')),
    CREDIT_CARD_NUMBER(7, 19, Checksum.LUHN, new CharacterRange('0', '9')),
    CANADIAN_SIN(9, 9, Checksum.LUHN, new CharacterRange('0', '9')),
    VIN(17, 17, Checksum.VIN,
            new CharacterRange('0', '9'),
            new CharacterRange('A', 'H'),
            new CharacterRange('J', 'N'),
            new CharacterRange('P', 'P'),
            new CharacterRange('R', 'Z')),
    DATE(10, 10, false, new CharacterRange('0', '9'));

    private final int minimumLength;
    private final int maximumLength;
    private final Checksum checksum;
    private final List<CharacterRange> alphabet;
    private final int radix;

    PIITag(int minimumLength, int maximumLength, CharacterRange... alphabetRanges) {
        this(minimumLength, maximumLength, false, alphabetRanges);
    }

    PIITag(int minimumLength, int maximumLength, boolean luhnCheckDigit, CharacterRange... alphabetRanges) {
        this(minimumLength, maximumLength, luhnCheckDigit ? Checksum.LUHN : Checksum.NONE, alphabetRanges);
    }

    PIITag(int minimumLength, int maximumLength, Checksum checksum, CharacterRange... alphabetRanges) {
        this.minimumLength = minimumLength;
        this.maximumLength = maximumLength;
        this.checksum = checksum;
        alphabet = List.of(alphabetRanges);
        radix = alphabet.stream().mapToInt(CharacterRange::length).sum();
    }

    int minimumLength() {
        return minimumLength;
    }

    int maximumLength() {
        return maximumLength;
    }

    int radix() {
        return radix;
    }

    boolean hasLuhnCheckDigit() {
        return checksum == Checksum.LUHN;
    }

    boolean hasVinCheckDigit() {
        return checksum == Checksum.VIN;
    }

    boolean hasCheckDigit() {
        return checksum != Checksum.NONE;
    }

    byte encode(char character) {
        var symbolOffset = 0;
        for (var range : alphabet) {
            if (range.contains(character)) {
                return (byte) (symbolOffset + character - range.firstCharacter());
            }
            symbolOffset += range.length();
        }
        throw new IllegalArgumentException("The value contains a character outside the tag's alphabet");
    }

    char decode(byte encodedCharacter) {
        var symbol = Byte.toUnsignedInt(encodedCharacter);
        for (var range : alphabet) {
            if (symbol < range.length()) {
                return (char) (range.firstCharacter() + symbol);
            }
            symbol -= range.length();
        }
        throw new IllegalArgumentException("The encoded character is outside the tag's alphabet");
    }

    private record CharacterRange(char firstCharacter, char lastCharacter) {
        int length() {
            return lastCharacter - firstCharacter + 1;
        }

        boolean contains(char character) {
            return character >= firstCharacter && character <= lastCharacter;
        }
    }

    enum Checksum {
        NONE,
        LUHN,
        VIN
    }
}
