package com.amalitech.labresultsvalidator.infrastructure.storage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AwsS3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(AwsS3Properties props) {
        // The dev/prod EC2 box has no instance role (this account denies iam:PassRole), so the
        // app authenticates with static keys injected as env vars. When those are absent (local
        // dev), fall back to the default provider chain (~/.aws credentials, env vars, etc.).
        AwsCredentialsProvider credentials =
            (props.accessKeyId() != null && !props.accessKeyId().isBlank())
                ? StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(props.accessKeyId(), props.secretAccessKey()))
                : DefaultCredentialsProvider.create();

        // Without an explicit call timeout, a network partition to S3 blocks the calling thread
        // indefinitely — putObject/getObject are called synchronously from sync job processing,
        // so a single hung call can stall an entire cohort sync run with no upper bound.
        ClientOverrideConfiguration overrideConfig = ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(60))
            .apiCallAttemptTimeout(Duration.ofSeconds(30))
            .build();

        return S3Client.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(credentials)
            .overrideConfiguration(overrideConfig)
            .build();
    }
}
