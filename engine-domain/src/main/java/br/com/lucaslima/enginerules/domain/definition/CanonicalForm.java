package br.com.lucaslima.enginerules.domain.definition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class CanonicalForm {

    private CanonicalForm() {
    }

    public static String of(PolicyDefinition definition) {
        return of((Object) PolicyDocument.write(definition));
    }

    public static String of(Object tree) {
        StringBuilder out = new StringBuilder();
        append(out, tree);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        switch (value) {
            case null -> out.append("null");
            case String text -> appendString(out, text);
            case Boolean bool -> out.append(bool);
            case Number number -> out.append(canonicalNumber(number));
            case Map<?, ?> map -> appendObject(out, map);
            case List<?> list -> appendArray(out, list);
            default -> appendString(out, value.toString());
        }
    }

    private static void appendObject(StringBuilder out, Map<?, ?> map) {
        Map<String, Object> sorted = new TreeMap<>();
        map.forEach((key, item) -> sorted.put(String.valueOf(key), item));
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            append(out, entry.getValue());
        }
        out.append('}');
    }

    private static void appendArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int index = 0; index < list.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            append(out, list.get(index));
        }
        out.append(']');
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Double || number instanceof Float) {
            double value = number.doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return "null";
            }
        }
        BigDecimal decimal = new BigDecimal(number.toString()).stripTrailingZeros();
        return decimal.signum() == 0 ? "0" : decimal.toPlainString();
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
}
