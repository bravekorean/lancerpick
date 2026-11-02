package com.example.lancerpick.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.lancerpick.common.text.TextNormalizer;
import org.junit.jupiter.api.Test;

class TextNormalizerTests {
    @Test
    void trimsSpecifiedUnicodeWhiteSpaceAndPreservesInternalText() {
        assertThat(TextNormalizer.normalize("\u0085\u00A0  한글 😀\r\n 두 번째\r줄 \u3000"))
                .isEqualTo("한글 😀\n 두 번째\n줄");
    }

    @Test
    void preservesCharactersOutsideTheSpecifiedWhiteSpaceSet() {
        assertThat(TextNormalizer.normalize("\uFEFF가\u200B"))
                .isEqualTo("\uFEFF가\u200B");
    }
}
