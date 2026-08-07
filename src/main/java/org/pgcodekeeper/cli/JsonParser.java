/*******************************************************************************
 * Copyright 2017-2026 TAXTELECOM, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.pgcodekeeper.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal strict JSON parser used for the batch manifest. Supports the full
 * JSON grammar (objects, arrays, strings with escapes, numbers, booleans,
 * null) and reports errors with line and column positions. Objects map to
 * {@link LinkedHashMap}, arrays to {@link ArrayList}, numbers to
 * {@link Double}, and duplicate object keys are rejected.
 */
final class JsonParser {

    /**
     * Signals malformed JSON with a 1-based line/column position.
     */
    static final class JsonException extends Exception {

        private static final long serialVersionUID = 1L;

        JsonException(String message, int line, int column) {
            super("%s at line %d column %d".formatted(message, line, column));
        }
    }

    private final String text;
    private int position;

    private JsonParser(String text) {
        this.text = text;
    }

    /**
     * Parses a complete JSON document.
     *
     * @param text the JSON text
     * @return the root value: {@code Map<String, Object>}, {@code List<Object>},
     *         {@code String}, {@code Double}, {@code Boolean} or {@code null}
     * @throws JsonException if the text is not a single well-formed JSON value
     */
    static Object parse(String text) throws JsonException {
        JsonParser parser = new JsonParser(text);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("Unexpected trailing content");
        }
        return value;
    }

    private Object readValue() throws JsonException {
        if (position >= text.length()) {
            throw error("Unexpected end of input");
        }
        char current = text.charAt(position);
        return switch (current) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() throws JsonException {
        expect('{');
        Map<String, Object> object = new LinkedHashMap<>();
        skipWhitespace();
        if (peekIs('}')) {
            position++;
            return object;
        }
        while (true) {
            skipWhitespace();
            if (!peekIs('"')) {
                throw error("Expected object key");
            }
            String key = readString();
            if (object.containsKey(key)) {
                throw error("Duplicate object key \"%s\"".formatted(key));
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            object.put(key, readValue());
            skipWhitespace();
            if (peekIs(',')) {
                position++;
                continue;
            }
            expect('}');
            return object;
        }
    }

    private List<Object> readArray() throws JsonException {
        expect('[');
        List<Object> array = new ArrayList<>();
        skipWhitespace();
        if (peekIs(']')) {
            position++;
            return array;
        }
        while (true) {
            skipWhitespace();
            array.add(readValue());
            skipWhitespace();
            if (peekIs(',')) {
                position++;
                continue;
            }
            expect(']');
            return array;
        }
    }

    private String readString() throws JsonException {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (true) {
            if (position >= text.length()) {
                throw error("Unterminated string");
            }
            char current = text.charAt(position++);
            if (current == '"') {
                return value.toString();
            }
            if (current == '\\') {
                value.append(readEscape());
            } else if (current < 0x20) {
                throw error("Unescaped control character in string");
            } else {
                value.append(current);
            }
        }
    }

    private char readEscape() throws JsonException {
        if (position >= text.length()) {
            throw error("Unterminated escape sequence");
        }
        char escape = text.charAt(position++);
        return switch (escape) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> readUnicodeEscape();
            default -> throw error("Invalid escape sequence \\" + escape);
        };
    }

    private char readUnicodeEscape() throws JsonException {
        if (position + 4 > text.length()) {
            throw error("Unterminated unicode escape");
        }
        String hex = text.substring(position, position + 4);
        try {
            char decoded = (char) Integer.parseInt(hex, 16);
            position += 4;
            return decoded;
        } catch (NumberFormatException ex) {
            throw error("Invalid unicode escape \\u" + hex);
        }
    }

    private Object readNumber() throws JsonException {
        int start = position;
        if (peekIs('-')) {
            position++;
        }
        while (position < text.length() && isNumberChar(text.charAt(position))) {
            position++;
        }
        String token = text.substring(start, position);
        try {
            return Double.valueOf(token);
        } catch (NumberFormatException ex) {
            position = start;
            throw error("Invalid JSON value");
        }
    }

    private static boolean isNumberChar(char candidate) {
        return candidate >= '0' && candidate <= '9'
                || candidate == '.' || candidate == 'e' || candidate == 'E'
                || candidate == '+' || candidate == '-';
    }

    private Object readKeyword(String keyword, Object value) throws JsonException {
        if (!text.startsWith(keyword, position)) {
            throw error("Invalid JSON value");
        }
        position += keyword.length();
        return value;
    }

    private boolean peekIs(char expected) {
        return position < text.length() && text.charAt(position) == expected;
    }

    private void expect(char expected) throws JsonException {
        if (!peekIs(expected)) {
            throw error("Expected '%s'".formatted(expected));
        }
        position++;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char current = text.charAt(position);
            if (current != ' ' && current != '\t' && current != '\n' && current != '\r') {
                return;
            }
            position++;
        }
    }

    private JsonException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < position && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonException(message, line, column);
    }
}
