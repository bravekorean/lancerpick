package com.example.lancerpick.common.text;

/** API 10절의 일반 문자열 규칙. 비밀번호에 사용하지 않는다. */
public final class TextNormalizer {
    private TextNormalizer() {}

    public static String normalize(String value) {
        String text = value.replace("\r\n", "\n").replace('\r', '\n');
        int start = 0;
        int end = text.length();
        while (start < end && isWhiteSpace(text.codePointAt(start))) {
            start += Character.charCount(text.codePointAt(start));
        }
        while (end > start && isWhiteSpace(text.codePointBefore(end))) {
            end -= Character.charCount(text.codePointBefore(end));
        }
        return text.substring(start, end);
    }

    private static boolean isWhiteSpace(int c) {
        return (c >= 0x0009 && c <= 0x000D) || c == 0x0020 || c == 0x0085 || c == 0x00A0
                || c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029
                || c == 0x202F || c == 0x205F || c == 0x3000;
    }
}
