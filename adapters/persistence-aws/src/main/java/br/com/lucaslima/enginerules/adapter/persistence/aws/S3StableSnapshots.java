package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import br.com.lucaslima.enginerules.adapter.json.Json;
import br.com.lucaslima.enginerules.adapter.persistence.documents.PolicyDocuments;
import br.com.lucaslima.enginerules.domain.catalog.Policy;
import br.com.lucaslima.enginerules.domain.catalog.PolicyName;
import br.com.lucaslima.enginerules.domain.catalog.PolicyVersion;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public final class S3StableSnapshots {

    static final String PREFIX = "stable/";

    private final S3Client s3;
    private final String bucket;

    public S3StableSnapshots(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public static String key(PolicyName name) {
        return PREFIX + name.value() + ".json";
    }

    public void export(Policy policy, long storedRevision, PolicyVersion stable) {
        if (!stable.version().equals(policy.stable())) {
            throw new IllegalArgumentException("snapshot of " + policy.name() + " must carry its stable version " + policy.stable());
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("policy", PolicyDocuments.fromSnapshot(policy.snapshot(), storedRevision));
        tree.put("stable", PolicyDocuments.fromVersion(stable));
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key(policy.name())).contentType("application/json").build(),
                RequestBody.fromString(Json.write(tree)));
    }

    public Optional<StableSnapshot> read(PolicyName name) {
        try {
            String content = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key(name)).build()).asUtf8String();
            Map<String, Object> tree = PolicyDocuments.object(Json.parse(content));
            return Optional.of(new StableSnapshot(
                    Policy.restore(PolicyDocuments.toSnapshot(PolicyDocuments.object(tree.get("policy")))),
                    PolicyDocuments.toVersion(PolicyDocuments.object(tree.get("stable")))));
        } catch (NoSuchKeyException missing) {
            return Optional.empty();
        }
    }

    public record StableSnapshot(Policy policy, PolicyVersion stable) {
    }
}
