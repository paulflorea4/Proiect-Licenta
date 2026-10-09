package com.gradingplatform.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 3.1a: what an enrollment code looks like. Uniqueness is the database's job, see `CourseServiceTests`. */
class EnrollCodeGeneratorTests {

    private final EnrollCodeGenerator generator = new EnrollCodeGenerator();

    @Test
    void aCodeIsEightSymbolsFromTheAlphabet() {
        for (int i = 0; i < 2_000; i++) {
            String code = generator.next();

            assertThat(code).hasSize(EnrollCodeGenerator.LENGTH).matches("[A-HJ-NP-Z2-9]{8}");
        }
    }

    @Test
    void noSymbolThatLooksLikeAnotherCanAppear() {
        // The alphabet itself, so the check does not depend on luck: none of 0 O 1 l I (or lower case).
        assertThat(EnrollCodeGenerator.ALPHABET).doesNotContain("0", "O", "1", "l", "I");
        assertThat(EnrollCodeGenerator.ALPHABET).isEqualTo(EnrollCodeGenerator.ALPHABET.toUpperCase());
        assertThat(EnrollCodeGenerator.ALPHABET).hasSize(32);
        assertThat(EnrollCodeGenerator.ALPHABET.chars().distinct().count()).isEqualTo(32);
    }

    @Test
    void everySymbolOfTheAlphabetIsUsed() {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 2_000; i++) {
            generator.next().chars().forEach(c -> seen.add((char) c));
        }

        // 16,000 draws over 32 symbols: missing one would mean a biased or truncated range.
        assertThat(seen).hasSize(EnrollCodeGenerator.ALPHABET.length());
    }

    @Test
    void codesDoNotRepeatInPractice() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 20_000; i++) {
            codes.add(generator.next());
        }

        assertThat(codes).hasSize(20_000);
    }

    @Test
    void whatAStudentTypedIsTrimmedAndUpperCased() {
        assertThat(EnrollCodeGenerator.normalize("abcd2345")).isEqualTo("ABCD2345");
        assertThat(EnrollCodeGenerator.normalize("  AbCd2345 \n")).isEqualTo("ABCD2345");
        assertThat(EnrollCodeGenerator.normalize("ABCD2345")).isEqualTo("ABCD2345");
    }

    @Test
    void normalizingDoesNotGuessAtLookAlikeSymbols() {
        // 0 and O, 1 and l are not aliased: a code containing them matches nothing.
        assertThat(EnrollCodeGenerator.normalize("0o1l")).isEqualTo("0O1L");
    }

    @Test
    void normalizingIgnoresTheDefaultLocale() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            // In Turkish "i".toUpperCase() is a dotted capital I; Locale.ROOT keeps it a plain I.
            assertThat(EnrollCodeGenerator.normalize("kitap")).isEqualTo("KITAP");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    void generatedCodesAreAlreadyNormal() {
        for (int i = 0; i < 500; i++) {
            String code = generator.next();
            assertThat(EnrollCodeGenerator.normalize(code)).isEqualTo(code);
        }
    }

    @Test
    void theCodeFitsItsColumn() {
        // V2: enroll_code VARCHAR(20).
        assertThat(EnrollCodeGenerator.LENGTH).isLessThanOrEqualTo(20);
    }
}
