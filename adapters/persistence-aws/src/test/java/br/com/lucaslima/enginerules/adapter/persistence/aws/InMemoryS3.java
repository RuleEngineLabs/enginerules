package br.com.lucaslima.enginerules.adapter.persistence.aws;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

final class InMemoryS3 implements S3Client {

    private final Map<String, Map<String, String>> buckets = new HashMap<>();
    private final List<CreateBucketRequest> created = new ArrayList<>();
    private boolean unavailable;

    void unavailable(boolean value) {
        unavailable = value;
    }

    void bucket(String name) {
        buckets.computeIfAbsent(name, ignored -> new HashMap<>());
    }

    List<CreateBucketRequest> created() {
        return created;
    }

    String object(String bucket, String key) {
        return buckets.get(bucket).get(key);
    }

    @Override
    public String serviceName() {
        return SERVICE_NAME;
    }

    @Override
    public void close() {
    }

    @Override
    public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
        available();
        try (InputStream content = body.contentStreamProvider().newStream()) {
            buckets.get(request.bucket()).put(request.key(), new String(content.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return PutObjectResponse.builder().build();
    }

    @Override
    public ResponseBytes<GetObjectResponse> getObjectAsBytes(GetObjectRequest request) {
        available();
        String content = buckets.get(request.bucket()).get(request.key());
        if (content == null) {
            throw NoSuchKeyException.builder().message("The specified key does not exist.").build();
        }
        return ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), content.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public CreateBucketResponse createBucket(CreateBucketRequest request) {
        available();
        if (buckets.containsKey(request.bucket())) {
            throw BucketAlreadyOwnedByYouException.builder().message("Your previous request to create the named bucket succeeded").build();
        }
        created.add(request);
        bucket(request.bucket());
        return CreateBucketResponse.builder().build();
    }

    private void available() {
        if (unavailable) {
            throw SdkClientException.create("Unable to execute HTTP request: Connection refused");
        }
    }
}
