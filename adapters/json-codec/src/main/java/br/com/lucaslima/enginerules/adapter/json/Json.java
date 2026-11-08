package br.com.lucaslima.enginerules.adapter.json;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Json {

    private Json() {
    }

    public static Object parse(String text) {
        Reader reader = new Reader(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw reader.error("unexpected trailing content");
        }
        return value;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> appendString(out, text);
            case Boolean bool -> out.append(bool);
            case Double number when number.isNaN() || number.isInfinite() -> out.append("null");
            case Float number when number.isNaN() || number.isInfinite() -> out.append("null");
            case Number number -> out.append(number);
            case Instant instant -> appendString(out, instant.toString());
            case Optional<?> optional -> append(out, optional.orElse(null));
            case Enum<?> constant -> appendString(out, constant.name());
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    appendString(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    append(out, entry.getValue());
                }
                out.append('}');
            }
            case Iterable<?> items -> {
                out.append('[');
                boolean first = true;
                for (Object item : items) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    append(out, item);
                }
                out.append(']');
            }
            default -> appendString(out, value.toString());
        }
    }

    private static void appendString(StringBuilder out, String text) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        private final String text;
        private int position;

        Reader(String text) {
            this.text = text;
        }

        Object value() {
            skipWhitespace();
            if (atEnd()) {
                throw error("unexpected end of input");
            }
            char current = text.charAt(position);
            return switch (current) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> keyword("true", Boolean.TRUE);
                case 'f' -> keyword("false", Boolean.FALSE);
                case 'n' -> keyword("null", null);
                default -> {
                    if (current == '-' || Character.isDigit(current)) {
                        yield number();
                    }
                    throw error("unexpected character '" + current + "'");
                }
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> result = new LinkedHashMap<>();
            position++;
            skipWhitespace();
            if (peek() == '}') {
                position++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected a field name");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                result.put(key, value());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                } else {
                    expect('}');
                    return result;
                }
            }
        }

        private List<Object> array() {
            List<Object> result = new ArrayList<>();
            position++;
            skipWhitespace();
            if (peek() == ']') {
                position++;
                return result;
            }
            while (true) {
                result.add(value());
                skipWhitespace();
                if (peek() == ',') {
                    position++;
                } else {
                    expect(']');
                    return result;
                }
            }
        }

        private String string() {
            position++;
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char current = text.charAt(position++);
                if (current == '"') {
                    return value.toString();
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (atEnd()) {
                    break;
                }
                char escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw error("truncated unicode escape");
                        }
                        value.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                        position += 4;
                    }
                    default -> throw error("invalid escape '\\" + escaped + "'");
                }
            }
            throw error("unterminated string");
        }

        private Object number() {
            int start = position;
            if (peek() == '-') {
                position++;
            }
            while (!atEnd() && "0123456789.eE+-".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            String literal = text.substring(start, position);
            try {
                if (literal.contains(".") || literal.contains("e") || literal.contains("E")) {
                    return Double.parseDouble(literal);
                }
                return Long.parseLong(literal);
            } catch (NumberFormatException invalid) {
                try {
                    return new BigDecimal(literal);
                } catch (NumberFormatException stillInvalid) {
                    throw error("invalid number '" + literal + "'");
                }
            }
        }

        private Object keyword(String keyword, Object value) {
            if (!text.startsWith(keyword, position)) {
                throw error("unexpected token");
            }
            position += keyword.length();
            return value;
        }

        private void expect(char expected) {
            if (peek() != expected) {
                throw error("expected '" + expected + "'");
            }
            position++;
        }

        private char peek() {
            return atEnd() ? '\0' : text.charAt(position);
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
        }

        boolean atEnd() {
            return position >= text.length();
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at position " + position + ": " + message);
        }
    }
}
