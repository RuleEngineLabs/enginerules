package br.com.lucaslima.enginerules.adapter.persistence.aws;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

public final class AwsClients {

    private AwsClients() {
    }

    public static DynamoDbClient dynamo(AwsSettings settings) {
        DynamoDbClientBuilder builder = DynamoDbClient.builder().region(Region.of(settings.region()));
        settings.endpoint().ifPresent(builder::endpointOverride);
        return builder.build();
    }

    public static S3Client s3(AwsSettings settings) {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(settings.region()));
        settings.endpoint().ifPresent(endpoint -> builder.endpointOverride(endpoint).forcePathStyle(true));
        return builder.build();
    }
}
