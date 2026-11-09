package br.com.lucaslima.enginerules.domain.rollout.shadow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import br.com.lucaslima.enginerules.domain.rollout.RolloutErrorCode;
import br.com.lucaslima.enginerules.domain.rollout.RolloutException;

final class IgnoredPaths {

    private static final String WILDCARD = "*";
    private static final Pattern FORMAT = Pattern.compile("data(\\.[A-Za-z_][A-Za-z0-9_]*(\\[\\*])?)+");

    private IgnoredPaths() {
    }

    static void validate(String path) {
        if (path == null || !FORMAT.matcher(path).matches()) {
            throw new RolloutException(RolloutErrorCode.INVALID_SHADOW,
                    "ignored path must look like data.field or data.items[*].field: " + path);
        }
    }

    static Object without(Object data, List<String> paths) {
        Object pruned = normalize(data);
        for (String path : paths) {
            pruned = remove(pruned, segments(path), 0);
        }
        return pruned;
    }

    private static List<String> segments(String path) {
        List<String> segments = new ArrayList<>();
        Arrays.stream(path.substring("data.".length()).split("\\.")).forEach(part -> {
            if (part.endsWith("[*]")) {
                segments.add(part.substring(0, part.length() - 3));
                segments.add(WILDCARD);
            } else {
                segments.add(part);
            }
        });
        return segments;
    }

    private static Object remove(Object node, List<String> segments, int depth) {
        String segment = segments.get(depth);
        boolean last = depth == segments.size() - 1;
        if (WILDCARD.equals(segment) && node instanceof List<?> list) {
            if (last) {
                return List.of();
            }
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(remove(item, segments, depth + 1)));
            return result;
        }
        if (node instanceof Map<?, ?> map && map.containsKey(segment)) {
            Map<Object, Object> result = new LinkedHashMap<>(map);
            if (last) {
                result.remove(segment);
            } else {
                result.put(segment, remove(map.get(segment), segments, depth + 1));
            }
            return result;
        }
        return node;
    }

    private static Object normalize(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> {
                if (value != null) {
                    result.put(key, normalize(value));
                }
            });
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(item -> result.add(normalize(item)));
            return result;
        }
        if (node instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        return node;
    }
}
