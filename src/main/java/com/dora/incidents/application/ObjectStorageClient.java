package com.dora.incidents.application;

import java.net.URL;
import java.time.Duration;

/**
 * Abstraction over object storage (MinIO locally, S3 in production — LLD-16 flip point).
 *
 * <p>The interface contract is intentionally narrow: presign PUT, presign GET, and exists.
 * All three operations are sufficient for the LLD-05 attachment lifecycle. Additional
 * operations (multipart, tagging, lifecycle policies) are LLD-16 scope.
 *
 * <p>LLD-16 can swap the implementation bean without any controller or service code changes:
 * the {@link S3ObjectStorageClient} already uses the AWS SDK v2 S3Client with an endpoint
 * override, so pointing it at real S3 is a config change not a code change.
 */
public interface ObjectStorageClient {

    /**
     * Generates a presigned PUT URL for the given storage key.
     *
     * @param key  the object key in the bucket (e.g. {@code incidents/<id>/<filename>})
     * @param ttl  how long the URL should remain valid
     * @return     a time-limited URL the client can PUT the file body against
     */
    URL presignPut(String key, Duration ttl);

    /**
     * Generates a presigned GET URL for the given storage key.
     *
     * @param key  the object key in the bucket
     * @param ttl  how long the URL should remain valid
     * @return     a time-limited URL for reading the object
     */
    URL presignGet(String key, Duration ttl);

    /**
     * Returns true if the object exists in the bucket. Used by the /complete endpoint
     * to verify the client actually uploaded the file before flipping status to READY.
     *
     * @param key  the object key in the bucket
     * @return     true iff the object exists
     */
    boolean exists(String key);
}
