package br.com.lucaslima.enginerules.domain.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface ValueTemplate {

    Object render(Map<String, Object> context);

    record Literal(Object value) implements ValueTemplate {
        @Override
        public Object render(Map<String, Object> context) {
            return value;
        }
    }

    record Expression(GuardedExpression expression) implements ValueTemplate {
        @Override
        public Object render(Map<String, Object> context) {
            return expression.evaluate(context);
        }
    }

    record ObjectTemplate(Map<String, ValueTemplate> fields) implements ValueTemplate {
        public ObjectTemplate {
            fields = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }

        @Override
        public Object render(Map<String, Object> context) {
            Map<String, Object> rendered = new LinkedHashMap<>();
            fields.forEach((name, template) -> rendered.put(name, template.render(context)));
            return rendered;
        }
    }

    record ArrayTemplate(List<ValueTemplate> items) implements ValueTemplate {
        public ArrayTemplate {
            items = List.copyOf(items);
        }

        @Override
        public Object render(Map<String, Object> context) {
            List<Object> rendered = new ArrayList<>(items.size());
            items.forEach(template -> rendered.add(template.render(context)));
            return rendered;
        }
    }
}
