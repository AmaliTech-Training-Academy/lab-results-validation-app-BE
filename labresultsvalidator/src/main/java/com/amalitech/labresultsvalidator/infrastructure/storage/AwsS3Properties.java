package com.amalitech.labresultsvalidator.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aws.s3")
public record AwsS3Properties(
    String region,
    String bucket,
    String accessKeyId,
    String secretAccessKey
) {}
