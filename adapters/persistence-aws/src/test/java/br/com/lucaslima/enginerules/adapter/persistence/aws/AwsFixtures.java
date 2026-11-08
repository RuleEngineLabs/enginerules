package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import br.com.lucaslima.enginerules.domain.catalog.Actor;
import br.com.lucaslima.enginerules.domain.catalog.ContentHash;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import br.com.lucaslima.enginerules.domain.catalog.SemanticVersion;
import br.com.lucaslima.enginerules.domain.definition.PolicyDefinition;
import br.com.lucaslima.enginerules.domain.definition.StateDefinition;

final class AwsFixtures {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    static final Actor ANA = new Actor("ana", Set.of("credit-team"));
    static final AwsSettings SETTINGS = new AwsSettings("sa-east-1", Optional.empty(), "test-", "snapshots");
    static final AwsTables TABLES = SETTINGS.tables();

    private AwsFixtures() {
    }

    static InMemoryDynamoDb dynamo() {
        InMemoryDynamoDb dynamo = new InMemoryDynamoDb();
        for (String table : List.of(TABLES.policies(), TABLES.policyIndex(), TABLES.audit(), TABLES.canaries(), TABLES.shadows())) {
            dynamo.createTestTable(table);
        }
        return dynamo;
    }

    static PolicyDefinition definition(int status) {
        return new PolicyDefinition("done", List.of(StateDefinition.builder("done", "response").status(status).build()));
    }

    static PolicyVersion version(String name, String version, int status) {
        return PolicyVersion.of(PolicyName.of(name), SemanticVersion.parse(version), definition(status));
    }

    static Policy created(String name, String owner) {
        return Policy.create(PolicyName.of(name), owner, Set.of(), ContentHash.of(definition(200)),
                new Actor("ana", Set.of(owner)), NOW);
    }
}
