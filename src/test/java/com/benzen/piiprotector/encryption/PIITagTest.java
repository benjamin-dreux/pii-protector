package com.benzen.piiprotector.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PIITagTest {
    @Test
    void email_defines_printable_ascii_and_latin1_alphabet() {
        assertEquals(191, PIITag.EMAIL.radix());
        assertEquals(3, PIITag.EMAIL.minimumLength());
        assertEquals(254, PIITag.EMAIL.maximumLength());
        assertEquals(0, Byte.toUnsignedInt(PIITag.EMAIL.encode(' ')));
        assertEquals(94, Byte.toUnsignedInt(PIITag.EMAIL.encode('~')));
        assertEquals(95, Byte.toUnsignedInt(PIITag.EMAIL.encode('\u00A0')));
        assertEquals(190, Byte.toUnsignedInt(PIITag.EMAIL.encode('\u00FF')));
    }

    @Test
    void email_alphabet_encoding_round_trips_supported_characters() {
        for (var character : new char[] {' ', '~', 'é', 'ÿ'}) {
            assertEquals(character, PIITag.EMAIL.decode(PIITag.EMAIL.encode(character)));
        }
    }

    @Test
    void email_rejects_characters_outside_its_alphabet() {
        assertThrows(IllegalArgumentException.class, () -> PIITag.EMAIL.encode('\u007F'));
        assertThrows(IllegalArgumentException.class, () -> PIITag.EMAIL.encode('ā'));
    }

    @Test
    void text_defines_printable_ascii_and_latin1_alphabet_and_length() {
        assertEquals(191, PIITag.TEXT.radix());
        assertEquals(3, PIITag.TEXT.minimumLength());
        assertEquals(1024, PIITag.TEXT.maximumLength());
        assertEquals('é', PIITag.TEXT.decode(PIITag.TEXT.encode('é')));
    }

    @Test
    void credit_card_number_defines_ascii_digit_alphabet() {
        assertEquals(10, PIITag.CREDIT_CARD_NUMBER.radix());
        assertEquals(7, PIITag.CREDIT_CARD_NUMBER.minimumLength());
        assertEquals(19, PIITag.CREDIT_CARD_NUMBER.maximumLength());
        assertEquals(0, Byte.toUnsignedInt(PIITag.CREDIT_CARD_NUMBER.encode('0')));
        assertEquals(9, Byte.toUnsignedInt(PIITag.CREDIT_CARD_NUMBER.encode('9')));
    }

    @Test
    void credit_card_number_alphabet_encoding_round_trips_digits() {
        for (var character = '0'; character <= '9'; character++) {
            assertEquals(character,
                    PIITag.CREDIT_CARD_NUMBER.decode(PIITag.CREDIT_CARD_NUMBER.encode(character)));
        }
    }

    @Test
    void credit_card_number_rejects_non_digit_characters() {
        assertThrows(IllegalArgumentException.class, () -> PIITag.CREDIT_CARD_NUMBER.encode('-'));
        assertThrows(IllegalArgumentException.class, () -> PIITag.CREDIT_CARD_NUMBER.encode('٤'));
    }

    @Test
    void vin_defines_seventeen_character_alphabet_without_i_o_or_q() {
        assertEquals(33, PIITag.VIN.radix());
        assertEquals(17, PIITag.VIN.minimumLength());
        assertEquals(17, PIITag.VIN.maximumLength());
        assertEquals(0, Byte.toUnsignedInt(PIITag.VIN.encode('0')));
        assertEquals(32, Byte.toUnsignedInt(PIITag.VIN.encode('Z')));
        assertThrows(IllegalArgumentException.class, () -> PIITag.VIN.encode('I'));
        assertThrows(IllegalArgumentException.class, () -> PIITag.VIN.encode('O'));
        assertThrows(IllegalArgumentException.class, () -> PIITag.VIN.encode('Q'));
    }

    @Test
    void canadian_sin_defines_nine_digit_luhn_alphabet() {
        assertEquals(10, PIITag.CANADIAN_SIN.radix());
        assertEquals(9, PIITag.CANADIAN_SIN.minimumLength());
        assertEquals(9, PIITag.CANADIAN_SIN.maximumLength());
        assertTrue(PIITag.CANADIAN_SIN.hasLuhnCheckDigit());
        assertEquals(0, Byte.toUnsignedInt(PIITag.CANADIAN_SIN.encode('0')));
        assertEquals(9, Byte.toUnsignedInt(PIITag.CANADIAN_SIN.encode('9')));
    }

    @Test
    void date_defines_eight_digit_alphabet_and_fixed_formatted_length() {
        assertEquals(10, PIITag.DATE.radix());
        assertEquals(10, PIITag.DATE.minimumLength());
        assertEquals(10, PIITag.DATE.maximumLength());
        assertEquals(0, Byte.toUnsignedInt(PIITag.DATE.encode('0')));
        assertEquals(9, Byte.toUnsignedInt(PIITag.DATE.encode('9')));
    }
}
