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
    void theCodeFitsItsColumn() {
        // V2: enroll_code VARCHAR(20).
        assertThat(EnrollCodeGenerator.LENGTH).isLessThanOrEqualTo(20);
    }
}
