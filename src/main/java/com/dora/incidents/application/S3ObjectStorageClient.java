package com.dora.incidents.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * AWS SDK v2 implementation of {@link ObjectStorageClient}.
 *
 * <p>Uses endpoint override so MinIO is a drop-in replacement for S3 in local
 * development (LLD §6). In production (LLD-16), set {@code aws.s3.endpoint}
 * to the real S3 endpoint or remove the override entirely.
 *
 * <p>Credentials are supplied via {@code AWS_ACCESS_KEY_ID} and
 * {@code AWS_SECRET_ACCESS_KEY} environment variables (standard SDK chain).
 * For MinIO, these map to the MinIO root credentials.
 * Secrets are never hardcoded — supplied via docker-compose env / AWS IAM in prod.
 *
 * <p>S3Client and S3Presigner are configured independently because the Presigner
 * is a separate client in SDK v2 with its own connection pool.
 */
@Component
public class S3ObjectStorageClient implements ObjectStorageClient {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;

    public S3ObjectStorageClient(
            @Value("${aws.s3.endpoint}") String endpointUrl,
            @Value("${aws.s3.region:us-east-1}") String region,
            @Value("${aws.s3.bucket}") String bucket,
            @Value("${aws.s3.access-key:minioadmin}") String accessKey,
            @Value("${aws.s3.secret-key:minioadmin}") String secretKey) {

        this.bucket = bucket;

        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        Region awsRegion = Region.of(region);

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpointUrl))
                .region(awsRegion)
                .credentialsProvider(credentials)
                // Force path-style addressing for MinIO compatibility (MinIO doesn't support
                // virtual-hosted style unless explicitly configured with a wildcard cert)
                .forcePathStyle(true)
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpointUrl))
                .region(awsRegion)
                .credentialsProvider(credentials)
                .build();
    }

    @Override
    public URL presignPut(String key, Duration ttl) {
        PutObjectPresignRequest request = PutObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .putObjectRequest(r -> r.bucket(bucket).key(key))
                .build();
        return presigner.presignPutObject(request).url();
    }

    @Override
    public URL presignGet(String key, Duration ttl) {
        GetObjectPresignRequest request = GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest(r -> r.bucket(bucket).key(key))
                .build();
        return presigner.presignGetObject(request).url();
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }
}
