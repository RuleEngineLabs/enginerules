package br.com.lucaslima.enginerules.domain.definition;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PolicyDocument {

    private static final Set<String> POLICY_FIELDS = Set.of("entry", "states", "id", "version");
    private static final Set<String> STATE_FIELDS = Set.of(
            "id", "kind", "transitions", "fallback", "contextKey", "map", "connection", "protocol", "service", "rpc",
            "message", "readOnly", "method", "path",
            "pathParams", "query", "body", "timeoutMs", "retry", "ttlSeconds", "over", "each",
            "maxConcurrency", "status", "data");
    private static final Set<String> TRANSITION_FIELDS = Set.of("when", "to");
    private static final Set<String> RETRY_FIELDS = Set.of("maxAttempts", "backoffMs");

    private PolicyDocument() {
    }

    public static PolicyDefinition read(Object tree) {
        Reader reader = new Reader();
        PolicyDefinition definition = reader.readPolicy(tree);
        if (!reader.problems.isEmpty()) {
            throw new InvalidPolicyDocumentException(reader.problems);
        }
        return definition;
    }

    public static Map<String, Object> write(PolicyDefinition definition) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("entry", definition.entry());
        List<Object> states = new ArrayList<>();
        definition.states().forEach(state -> states.add(writeState(state)));
        tree.put("states", states);
        return tree;
    }

    private static Map<String, Object> writeState(StateDefinition state) {
        Map<String, Object> tree = new LinkedHashMap<>();
        putIfPresent(tree, "id", state.id());
        putIfPresent(tree, "kind", state.kind());
        if (state.transitions() != null) {
            List<Object> transitions = new ArrayList<>();
            state.transitions().forEach(transition -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("when", transition.when());
                item.put("to", transition.to());
                transitions.add(item);
            });
            tree.put("transitions", transitions);
        }
        putIfPresent(tree, "fallback", state.fallback());
        putIfPresent(tree, "contextKey", state.contextKey());
        putIfPresent(tree, "map", state.map());
        putIfPresent(tree, "connection", state.connection());
        putIfPresent(tree, "protocol", state.protocol());
        putIfPresent(tree, "service", state.service());
        putIfPresent(tree, "rpc", state.rpc());
        putIfPresent(tree, "message", state.message());
        putIfPresent(tree, "readOnly", state.readOnly());
        putIfPresent(tree, "method", state.method());
        putIfPresent(tree, "path", state.path());
        putIfPresent(tree, "pathParams", state.pathParams());
        putIfPresent(tree, "query", state.query());
        putIfPresent(tree, "body", state.body());
        putIfPresent(tree, "timeoutMs", state.timeoutMs());
        if (state.retry() != null) {
            Map<String, Object> retry = new LinkedHashMap<>();
            putIfPresent(retry, "maxAttempts", state.retry().maxAttempts());
            if (!state.retry().backoffMs().isEmpty()) {
                retry.put("backoffMs", new ArrayList<Object>(state.retry().backoffMs()));
            }
            tree.put("retry", retry);
        }
        putIfPresent(tree, "ttlSeconds", state.ttlSeconds());
        putIfPresent(tree, "over", state.over());
        putIfPresent(tree, "each", state.each());
        putIfPresent(tree, "maxConcurrency", state.maxConcurrency());
        putIfPresent(tree, "status", state.status());
        putIfPresent(tree, "data", state.data());
        return tree;
    }

    private static void putIfPresent(Map<String, Object> tree, String key, Object value) {
        if (value != null) {
            tree.put(key, value);
        }
    }

    private static final class Reader {
        private final List<String> problems = new ArrayList<>();

        PolicyDefinition readPolicy(Object tree) {
            Map<String, Object> policy = asObject(tree, "policy");
            if (policy == null) {
                return new PolicyDefinition(null, List.of());
            }
            rejectUnknownFields(policy, POLICY_FIELDS, "policy");
            String entry = text(policy.get("entry"), "policy.entry");
            List<StateDefinition> states = new ArrayList<>();
            List<Object> rawStates = asArray(policy.get("states"), "policy.states");
            if (rawStates != null) {
                for (int index = 0; index < rawStates.size(); index++) {
                    StateDefinition state = readState(rawStates.get(index), "states[" + index + "]");
                    if (state != null) {
                        states.add(state);
                    }
                }
            }
            return new PolicyDefinition(entry, states);
        }

        private StateDefinition readState(Object tree, String location) {
            Map<String, Object> state = asObject(tree, location);
            if (state == null) {
                return null;
            }
            String id = text(state.get("id"), location + ".id");
            String where = id == null ? location : "state '" + id + "'";
            rejectUnknownFields(state, STATE_FIELDS, where);
            return StateDefinition.builder(id, text(state.get("kind"), where + ".kind"))
                    .transitions(readTransitions(state.get("transitions"), where))
                    .fallback(text(state.get("fallback"), where + ".fallback"))
                    .contextKey(text(state.get("contextKey"), where + ".contextKey"))
                    .map(textMap(state.get("map"), where + ".map"))
                    .connection(text(state.get("connection"), where + ".connection"))
                    .protocol(text(state.get("protocol"), where + ".protocol"))
                    .service(text(state.get("service"), where + ".service"))
                    .rpc(text(state.get("rpc"), where + ".rpc"))
                    .message(state.get("message"))
                    .readOnly(bool(state.get("readOnly"), where + ".readOnly"))
                    .method(text(state.get("method"), where + ".method"))
                    .path(text(state.get("path"), where + ".path"))
                    .pathParams(textMap(state.get("pathParams"), where + ".pathParams"))
                    .query(textMap(state.get("query"), where + ".query"))
                    .body(state.get("body"))
                    .timeoutMs(integer(state.get("timeoutMs"), where + ".timeoutMs"))
                    .retry(readRetry(state.get("retry"), where + ".retry"))
                    .ttlSeconds(integer(state.get("ttlSeconds"), where + ".ttlSeconds"))
                    .over(text(state.get("over"), where + ".over"))
                    .each(text(state.get("each"), where + ".each"))
                    .maxConcurrency(integer(state.get("maxConcurrency"), where + ".maxConcurrency"))
                    .status(integer(state.get("status"), where + ".status"))
                    .data(state.get("data"))
                    .build();
        }

        private List<Transition> readTransitions(Object tree, String where) {
            if (tree == null) {
                return null;
            }
            List<Object> raw = asArray(tree, where + ".transitions");
            if (raw == null) {
                return null;
            }
            List<Transition> transitions = new ArrayList<>();
            for (int index = 0; index < raw.size(); index++) {
                String location = where + ".transitions[" + index + "]";
                Map<String, Object> transition = asObject(raw.get(index), location);
                if (transition != null) {
                    rejectUnknownFields(transition, TRANSITION_FIELDS, location);
                    transitions.add(new Transition(
                            text(transition.get("when"), location + ".when"),
                            text(transition.get("to"), location + ".to")));
                }
            }
            return transitions;
        }

        private RetryDefinition readRetry(Object tree, String where) {
            if (tree == null) {
                return null;
            }
            Map<String, Object> retry = asObject(tree, where);
            if (retry == null) {
                return null;
            }
            rejectUnknownFields(retry, RETRY_FIELDS, where);
            List<Long> backoff = new ArrayList<>();
            List<Object> rawBackoff = retry.get("backoffMs") == null ? List.of() : asArray(retry.get("backoffMs"), where + ".backoffMs");
            if (rawBackoff != null) {
                for (int index = 0; index < rawBackoff.size(); index++) {
                    Integer gap = integer(rawBackoff.get(index), where + ".backoffMs[" + index + "]");
                    if (gap != null) {
                        backoff.add(gap.longValue());
                    }
                }
            }
            return new RetryDefinition(integer(retry.get("maxAttempts"), where + ".maxAttempts"), backoff);
        }

        private void rejectUnknownFields(Map<String, Object> tree, Set<String> allowed, String where) {
            tree.keySet().stream()
                    .filter(field -> !allowed.contains(field))
                    .forEach(field -> problems.add(where + ": unknown field '" + field + "'"));
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> asObject(Object value, String where) {
            if (value instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
            problems.add(where + ": expected an object");
            return null;
        }

        @SuppressWarnings("unchecked")
        private List<Object> asArray(Object value, String where) {
            if (value instanceof List<?> list) {
                return (List<Object>) list;
            }
            problems.add(where + ": expected an array");
            return null;
        }

        private String text(Object value, String where) {
            if (value == null || value instanceof String) {
                return (String) value;
            }
            problems.add(where + ": expected a string");
            return null;
        }

        private Boolean bool(Object value, String where) {
            if (value == null || value instanceof Boolean) {
                return (Boolean) value;
            }
            problems.add(where + ": expected a boolean");
            return null;
        }

        private Map<String, String> textMap(Object value, String where) {
            if (value == null) {
                return null;
            }
            Map<String, Object> raw = asObject(value, where);
            if (raw == null) {
                return null;
            }
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, item) -> {
                String text = text(item, where + "." + key);
                if (text != null) {
                    result.put(key, text);
                }
            });
            return result;
        }

        private Integer integer(Object value, String where) {
            if (value == null) {
                return null;
            }
            if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                    || value instanceof BigInteger) {
                return exactInt(((Number) value).longValue(), where);
            }
            if (value instanceof Number number) {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.stripTrailingZeros().scale() <= 0) {
                    return exactInt(decimal.longValue(), where);
                }
            }
            problems.add(where + ": expected an integer");
            return null;
        }

        private Integer exactInt(long value, String where) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                problems.add(where + ": integer out of range");
                return null;
            }
            return (int) value;
        }
    }
}
