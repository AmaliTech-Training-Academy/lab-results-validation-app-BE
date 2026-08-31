package com.amalitech.labresultsvalidator.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An in-memory stand-in for S3, replacing the real client for integration tests.
 *
 * <p>A mock returning nulls would do for "the code ran", but the archive is not incidental to what
 * these tests check: the run records an S3 version id per file, and the archive is what marks a
 * version as processed — {@code CohortSyncJobRunner} sequences the upload *after* processing
 * precisely so that a failure leaves the previous baseline in place. A store that actually
 * remembers what it was handed lets a test assert that, and lets it read the archived bytes back.
 *
 * <p>Only the two methods {@code S3StorageService} uses are implemented; anything else throws, so a
 * new call site cannot silently pass against a stub that quietly does nothing.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestStorageConfig {

    /** Exposed so a test can assert on what was archived, and clear between tests. */
    public static class InMemoryS3 {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        private final AtomicInteger version = new AtomicInteger();

        public byte[] get(String key) {
            return objects.get(key);
        }

        public boolean contains(String key) {
            return objects.containsKey(key);
        }

        public int size() {
            return objects.size();
        }

        public void clear() {
            objects.clear();
        }

        String put(String key, byte[] content) {
            objects.put(key, content);
            return "v" + version.incrementAndGet();
        }
    }

    @Bean
    InMemoryS3 inMemoryS3() {
        return new InMemoryS3();
    }

    @Bean
    @Primary
    S3Client testS3Client(InMemoryS3 store) {
        return new S3Client() {
            @Override
            public String serviceName() {
                return "s3";
            }

            @Override
            public void close() {
                // nothing to release
            }

            @Override
            public PutObjectResponse putObject(PutObjectRequest request, RequestBody body) {
                byte[] content;
                try (InputStream in = body.contentStreamProvider().newStream()) {
                    content = in.readAllBytes();
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException("Could not read upload body", ex);
                }
                return PutObjectResponse.builder()
                    .versionId(store.put(request.key(), content))
                    .build();
            }

            @Override
            public software.amazon.awssdk.core.ResponseInputStream<
                software.amazon.awssdk.services.s3.model.GetObjectResponse> getObject(
                    GetObjectRequest request) {
                byte[] content = store.get(request.key());
                if (content == null) {
                    throw NoSuchKeyException.builder()
                        .message("No such key: " + request.key())
                        .build();
                }
                InputStream stream = new ByteArrayInputStream(content);
                return new software.amazon.awssdk.core.ResponseInputStream<>(
                    software.amazon.awssdk.services.s3.model.GetObjectResponse.builder()
                        .contentLength((long) content.length)
                        .build(),
                    software.amazon.awssdk.http.AbortableInputStream.create(stream));
            }
        };
    }
}
