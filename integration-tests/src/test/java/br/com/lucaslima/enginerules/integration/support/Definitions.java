package br.com.lucaslima.enginerules.integration.support;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Definitions {

    private Definitions() {
    }

    public static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < keysAndValues.length; index += 2) {
            map.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return map;
    }

    public static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }

    public static Map<String, Object> policy(String entry, Object... states) {
        return obj("entry", entry, "states", list(states));
    }

    public static Map<String, Object> respond(int status, Map<String, Object> data) {
        return policy("done", response("done", status, data));
    }

    public static Map<String, Object> response(String id, int status, Map<String, Object> data) {
        return data == null ? obj("id", id, "kind", "response", "status", status)
                : obj("id", id, "kind", "response", "status", status, "data", data);
    }

    public static Map<String, Object> execution(String id, Map<String, Object> map, String contextKey, List<Object> transitions,
                                                String fallback) {
        return obj("id", id, "kind", "execution", "map", map, "contextKey", contextKey, "transitions", transitions,
                "fallback", fallback);
    }

    public static Map<String, Object> when(String condition, String target) {
        return obj("when", condition, "to", target);
    }

    public static Map<String, Object> ageGate(int minimumAge) {
        return policy("evaluate",
                execution("evaluate", obj("adult", "input.age >= " + minimumAge), "decision",
                        list(when("decision.adult", "approved")), "denied"),
                response("approved", 200, obj("approved", "true", "minimumAge", "'" + minimumAge + "'")),
                response("denied", 403, obj("approved", "false")));
    }
}
