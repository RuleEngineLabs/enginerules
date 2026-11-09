package br.com.lucaslima.enginerules.domain.definition;

import java.util.List;
import java.util.Map;

public record StateDefinition(
        String id,
        String kind,
        List<Transition> transitions,
        String fallback,
        String contextKey,
        Map<String, String> map,
        String connection,
        String protocol,
        String service,
        String rpc,
        Object message,
        Boolean readOnly,
        String method,
        String path,
        Map<String, String> pathParams,
        Map<String, String> query,
        Object body,
        Integer timeoutMs,
        RetryDefinition retry,
        Integer ttlSeconds,
        String over,
        String each,
        Integer maxConcurrency,
        Integer status,
        Object data) {

    public StateDefinition {
        transitions = transitions == null ? null : OrderedCopies.listOrEmpty(transitions);
        map = OrderedCopies.mapOrNull(map);
        pathParams = OrderedCopies.mapOrNull(pathParams);
        query = OrderedCopies.mapOrNull(query);
    }

    public List<Transition> transitionsOrEmpty() {
        return transitions == null ? List.of() : transitions;
    }

    public static Builder builder(String id, String kind) {
        return new Builder(id, kind);
    }

    public static final class Builder {
        private final String id;
        private final String kind;
        private List<Transition> transitions;
        private String fallback;
        private String contextKey;
        private Map<String, String> map;
        private String connection;
        private String protocol;
        private String service;
        private String rpc;
        private Object message;
        private Boolean readOnly;
        private String method;
        private String path;
        private Map<String, String> pathParams;
        private Map<String, String> query;
        private Object body;
        private Integer timeoutMs;
        private RetryDefinition retry;
        private Integer ttlSeconds;
        private String over;
        private String each;
        private Integer maxConcurrency;
        private Integer status;
        private Object data;

        private Builder(String id, String kind) {
            this.id = id;
            this.kind = kind;
        }

        public Builder transitions(List<Transition> value) { this.transitions = value; return this; }
        public Builder fallback(String value) { this.fallback = value; return this; }
        public Builder contextKey(String value) { this.contextKey = value; return this; }
        public Builder map(Map<String, String> value) { this.map = value; return this; }
        public Builder connection(String value) { this.connection = value; return this; }
        public Builder protocol(String value) { this.protocol = value; return this; }
        public Builder service(String value) { this.service = value; return this; }
        public Builder rpc(String value) { this.rpc = value; return this; }
        public Builder message(Object value) { this.message = value; return this; }
        public Builder readOnly(Boolean value) { this.readOnly = value; return this; }
        public Builder method(String value) { this.method = value; return this; }
        public Builder path(String value) { this.path = value; return this; }
        public Builder pathParams(Map<String, String> value) { this.pathParams = value; return this; }
        public Builder query(Map<String, String> value) { this.query = value; return this; }
        public Builder body(Object value) { this.body = value; return this; }
        public Builder timeoutMs(Integer value) { this.timeoutMs = value; return this; }
        public Builder retry(RetryDefinition value) { this.retry = value; return this; }
        public Builder ttlSeconds(Integer value) { this.ttlSeconds = value; return this; }
        public Builder over(String value) { this.over = value; return this; }
        public Builder each(String value) { this.each = value; return this; }
        public Builder maxConcurrency(Integer value) { this.maxConcurrency = value; return this; }
        public Builder status(Integer value) { this.status = value; return this; }
        public Builder data(Object value) { this.data = value; return this; }

        public StateDefinition build() {
            return new StateDefinition(id, kind, transitions, fallback, contextKey, map, connection, protocol, service, rpc,
                    message, readOnly, method, path,
                    pathParams, query, body, timeoutMs, retry, ttlSeconds, over, each, maxConcurrency, status, data);
        }
    }
}
