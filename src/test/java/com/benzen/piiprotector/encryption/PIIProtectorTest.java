package com.benzen.piiprotector.encryption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PIIProtectorTest {
    private static final byte[] TEST_KEY = new byte[32];
    private static final DateTimeFormatter STRICT_DATE_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    @Nested
    class EmailTests {
        @Test
        void encrypts_email_with_latin1_characters_without_changing_length_or_alphabet() {
            PIIProtector engine = new PIIProtector(TEST_KEY);
            String email = "élise@example.test";

            String encrypted = engine.encrypt(PIITag.EMAIL, email);

            assertEquals("7_YÝÜã¶[)2öÑ*Ss$¢Ö", encrypted);
            assertNotEquals(email, encrypted);
            assertEquals(email.length(), encrypted.length());
            assertTrue(encrypted.chars().allMatch(PIIProtectorTest::isSupportedCharacter));
        }

        @Test
        void encrypts_accented_characters() {
            PIIProtector engine = new PIIProtector(TEST_KEY);
            String accentedValue = "éèàç";

            String encrypted = engine.encrypt(PIITag.EMAIL, accentedValue);

            assertNotEquals(accentedValue, encrypted);
            assertEquals(accentedValue.length(), encrypted.length());
            assertTrue(encrypted.chars().allMatch(PIIProtectorTest::isSupportedCharacter));
        }

        @Test
        void rejects_email_values_shorter_than_minimum() {
            PIIProtector engine = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class, () -> engine.encrypt(PIITag.EMAIL, "ab"));
        }

        @Test
        void rejects_email_values_longer_than_maximum() {
            var protector = new PIIProtector(TEST_KEY);
            var email = "a".repeat(255);

            assertThrows(IllegalArgumentException.class, () -> protector.encrypt(PIITag.EMAIL, email));
        }

        @Test
        void rejects_characters_outside_printable_ascii_and_latin1() {
            PIIProtector engine = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class, () -> engine.encrypt(PIITag.EMAIL, "ābc@example.com"));
        }
    }

    @Nested
    class TextTests {
        @Test
        void encrypts_plain_text_without_changing_length_or_alphabet() {
            var protector = new PIIProtector(TEST_KEY);
            var text = "Texte simple avec accents : déjà prêt.";

            var encrypted = protector.encrypt(PIITag.TEXT, text);

            assertEquals("\"íJ`ÆQ|¿9Â¢|u¸ê:lâi ºÈ;%áõ¶,³m`êÏé¦Ùtm", encrypted);
            assertNotEquals(text, encrypted);
            assertEquals(text.length(), encrypted.length());
            assertTrue(encrypted.chars().allMatch(PIIProtectorTest::isSupportedCharacter));
        }

        @Test
        void rejects_text_outside_supported_alphabet() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.TEXT, "Texte avec ā"));
        }

        @Test
        void rejects_text_outside_allowed_length() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class, () -> protector.encrypt(PIITag.TEXT, "ab"));
            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.TEXT, "a".repeat(1025)));
        }

        @Test
        void encrypts_text_at_maximum_supported_length() {
            var protector = new PIIProtector(TEST_KEY);
            var text = "a".repeat(1024);

            var encrypted = protector.encrypt(PIITag.TEXT, text);

            assertNotEquals(text, encrypted);
            assertEquals(1024, encrypted.length());
            assertTrue(encrypted.chars().allMatch(PIIProtectorTest::isSupportedCharacter));
        }
    }

    @Nested
    class CreditCardNumberTests {
        @Test
        void encrypts_credit_card_number_without_changing_length_or_digit_format() {
            var protector = new PIIProtector(TEST_KEY);
            var creditCardNumber = "4111111111111111";

            var encrypted = protector.encrypt(PIITag.CREDIT_CARD_NUMBER, creditCardNumber);

            assertEquals("5807352318252862", encrypted);
            assertNotEquals(creditCardNumber, encrypted);
            assertEquals(creditCardNumber.length(), encrypted.length());
            assertTrue(encrypted.chars().allMatch(character -> character >= '0' && character <= '9'));
        }

        @Test
        void encrypts_credit_card_number_to_luhn_valid_number() {
            var protector = new PIIProtector(TEST_KEY);
            var creditCardNumber = "4111111111111111";

            var encrypted = protector.encrypt(PIITag.CREDIT_CARD_NUMBER, creditCardNumber);

            assertTrue(isLuhnValid(encrypted));
        }

        @Test
        void rejects_credit_card_number_with_invalid_luhn_checksum() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CREDIT_CARD_NUMBER, "4111111111111112"));
        }

        @Test
        void rejects_credit_card_number_with_non_ascii_digits() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CREDIT_CARD_NUMBER, "٤١١١١١١١١١١١١١١١"));
        }

        @Test
        void rejects_credit_card_number_shorter_than_minimum() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CREDIT_CARD_NUMBER, "12345"));
        }

        @Test
        void rejects_credit_card_number_longer_than_maximum() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CREDIT_CARD_NUMBER, "1".repeat(20)));
        }
    }

    @Nested
    class CanadianSinTests {
        @Test
        void encrypts_canadian_sin_and_preserves_luhn_validity() {
            var protector = new PIIProtector(TEST_KEY);
            var sin = "046454286";

            var encrypted = protector.encrypt(PIITag.CANADIAN_SIN, sin);

            assertEquals("798753851", encrypted);
            assertNotEquals(sin, encrypted);
            assertEquals(sin.length(), encrypted.length());
            assertTrue(encrypted.chars().allMatch(character -> character >= '0' && character <= '9'));
            assertTrue(isLuhnValid(encrypted));
        }

        @Test
        void rejects_canadian_sin_with_invalid_luhn_checksum() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CANADIAN_SIN, "046454287"));
        }

        @Test
        void rejects_canadian_sin_with_invalid_length_or_characters() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CANADIAN_SIN, "04645428"));
            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.CANADIAN_SIN, "04645A286"));
        }
    }

    @Nested
    class VinTests {
        @Test
        void encrypts_vin_and_preserves_format_and_checksum() {
            var protector = new PIIProtector(TEST_KEY);
            var vin = "1HGCM82633A004352";

            var encrypted = protector.encrypt(PIITag.VIN, vin);

            assertEquals("UEU51CBJ6M6M6C6FE", encrypted);
            assertNotEquals(vin, encrypted);
            assertEquals(17, encrypted.length());
            assertTrue(encrypted.chars().allMatch(character ->
                    character >= '0' && character <= '9'
                            || character >= 'A' && character <= 'H'
                            || character >= 'J' && character <= 'N'
                            || character == 'P'
                            || character >= 'R' && character <= 'Z'));
            assertTrue(isVinCheckDigitValid(encrypted));
        }

        @Test
        void rejects_vin_with_invalid_checksum() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.VIN, "1HGCM82643A004352"));
        }

        @Test
        void rejects_vin_with_invalid_characters_or_length() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.VIN, "1HGCM82633I004352"));
            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.VIN, "1HGCM82633A00435"));
        }
    }

    @Nested
    class DateTests {
        @Test
        void encrypts_date_without_changing_format_and_returns_a_valid_date() {
            var protector = new PIIProtector(TEST_KEY);
            var date = "2024-02-29";

            var encrypted = protector.encrypt(PIITag.DATE, date);

            assertEquals("6384-04-15", encrypted);
            assertNotEquals(date, encrypted);
            assertEquals(date.length(), encrypted.length());
            assertTrue(encrypted.matches("\\d{4}-\\d{2}-\\d{2}"));
            assertTrue(LocalDate.parse(encrypted, STRICT_DATE_FORMAT).getYear() >= 1);
        }

        @Test
        void rejects_invalid_calendar_dates() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.DATE, "2023-02-29"));
        }

        @Test
        void rejects_dates_not_in_iso_format() {
            var protector = new PIIProtector(TEST_KEY);

            assertThrows(IllegalArgumentException.class,
                    () -> protector.encrypt(PIITag.DATE, "29/02/2024"));
        }
    }

    private static boolean isSupportedCharacter(int character) {
        return character >= 32 && character <= 126 || character >= 160 && character <= 255;
    }

    private static boolean isLuhnValid(String value) {
        var sum = 0;
        var doubleDigit = false;
        for (var i = value.length() - 1; i >= 0; i--) {
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
        return sum % 10 == 0;
    }

    private static boolean isVinCheckDigitValid(String vin) {
        var weights = new int[] {8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2};
        var weightedSum = 0;
        for (var index = 0; index < weights.length; index++) {
            if (index != 8) {
                weightedSum += vinCharacterValue(vin.charAt(index)) * weights[index];
            }
        }
        var remainder = weightedSum % 11;
        var checkDigit = remainder == 10 ? 'X' : (char) ('0' + remainder);
        return vin.charAt(8) == checkDigit;
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
            default -> throw new IllegalArgumentException("Invalid VIN character");
        };
    }
}
