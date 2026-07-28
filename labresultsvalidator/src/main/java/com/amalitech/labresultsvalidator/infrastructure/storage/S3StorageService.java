package com.amalitech.labresultsvalidator.infrastructure.storage;

import com.amalitech.labresultsvalidator.infrastructure.storage.exception.S3StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;

/**
 * Basic CRUD over the SharePoint-file bucket. The bucket is versioned, so overwriting a key
 * keeps the prior version (used to diff instructor edits and re-trigger validation).
 */
@Service
public class S3StorageService {

    private static final Logger LOG = LoggerFactory.getLogger(S3StorageService.class);

    private final S3Client s3Client;
    private final AwsS3Properties props;

    public S3StorageService(S3Client s3Client, AwsS3Properties props) {
        this.s3Client = s3Client;
        this.props = props;
    }

    /**
     * Create or overwrite an object. Returns the S3 version id of the written object
     * (null when bucket versioning is disabled).
     */
    public String putObject(String key, byte[] content, String contentType) {
        try {
            PutObjectResponse response = s3Client.putObject(
                PutObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .contentType(contentType)
                    .build(),
                RequestBody.fromBytes(content));
            LOG.debug("Uploaded s3://{}/{} ({} bytes, versionId={})",
                props.bucket(), key, content.length, response.versionId());
            return response.versionId();
        } catch (SdkException ex) {
            LOG.warn("S3 putObject failed for key {}: {}", key, ex.getMessage());
            throw new S3StorageException("Failed to upload object to S3 (key=" + key + ").", ex);
        }
    }

    /** Read an object's full content. Throws {@link S3StorageException} if the key is missing. */
    public byte[] getObject(String key) {
        try (ResponseInputStream<GetObjectResponse> stream = s3Client.getObject(
                GetObjectRequest.builder()
                    .bucket(props.bucket())
                    .key(key)
                    .build())) {
            return stream.readAllBytes();
        } catch (NoSuchKeyException ex) {
            throw new S3StorageException("Object not found in S3 (key=" + key + ").", ex);
        } catch (IOException | SdkException ex) {
            LOG.warn("S3 getObject failed for key {}: {}", key, ex.getMessage());
            throw new S3StorageException("Failed to read object from S3 (key=" + key + ").", ex);
        }
    }

    /** True if an object exists at the given key. */
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build());
            return true;
        } catch (NoSuchKeyException ex) {
            return false;
        } catch (S3Exception ex) {
            // HEAD carries no error body, so a missing key surfaces as a generic 404 S3Exception
            // rather than NoSuchKeyException. Treat 404 as "does not exist".
            if (ex.statusCode() == 404) {
                return false;
            }
            LOG.warn("S3 headObject failed for key {}: {}", key, ex.getMessage());
            throw new S3StorageException("Failed to check object existence in S3 (key=" + key + ").", ex);
        } catch (SdkException ex) {
            LOG.warn("S3 headObject failed for key {}: {}", key, ex.getMessage());
            throw new S3StorageException("Failed to check object existence in S3 (key=" + key + ").", ex);
        }
    }

    /** Delete an object. No-op (from the caller's view) if the key does not exist. */
    public void deleteObject(String key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .build());
            LOG.debug("Deleted s3://{}/{}", props.bucket(), key);
        } catch (SdkException ex) {
            LOG.warn("S3 deleteObject failed for key {}: {}", key, ex.getMessage());
            throw new S3StorageException("Failed to delete object from S3 (key=" + key + ").", ex);
        }
    }
}
