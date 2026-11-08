package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

final class Items {

    static final String POLICY = "policyName";
    static final String SORT = "sk";
    static final String DOCUMENT = "doc";

    private Items() {
    }

    static AttributeValue text(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue number(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    static AttributeValue flag(boolean value) {
        return AttributeValue.fromBool(value);
    }

    static AttributeValue document(Object tree) {
        return AttributeValue.fromS(Json.write(tree));
    }

    static Map<String, Object> document(Map<String, AttributeValue> item) {
        return PolicyDocuments.object(Json.parse(item.get(DOCUMENT).s()));
    }

    static Optional<String> text(Map<String, AttributeValue> item, String attribute) {
        return Optional.ofNullable(item.get(attribute)).map(AttributeValue::s);
    }

    static Map<String, AttributeValue> key(String partition, String sort) {
        Map<String, AttributeValue> key = new LinkedHashMap<>();
        key.put(POLICY, text(partition));
        key.put(SORT, text(sort));
        return key;
    }

    static Map<String, AttributeValue> values(Object... namesAndValues) {
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        for (int index = 0; index + 1 < namesAndValues.length; index += 2) {
            values.put((String) namesAndValues[index], (AttributeValue) namesAndValues[index + 1]);
        }
        return values;
    }

    static Map<String, String> names(String... placeholdersAndNames) {
        Map<String, String> names = new LinkedHashMap<>();
        for (int index = 0; index + 1 < placeholdersAndNames.length; index += 2) {
            names.put(placeholdersAndNames[index], placeholdersAndNames[index + 1]);
        }
        return names;
    }
}
