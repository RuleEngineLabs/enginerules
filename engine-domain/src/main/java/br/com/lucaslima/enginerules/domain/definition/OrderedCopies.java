package br.com.lucaslima.enginerules.domain.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OrderedCopies {

    private OrderedCopies() {
    }

    static <V> Map<String, V> mapOrNull(Map<String, V> source) {
        return source == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    static <T> List<T> listOrEmpty(List<T> source) {
        return source == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(source));
    }
}
