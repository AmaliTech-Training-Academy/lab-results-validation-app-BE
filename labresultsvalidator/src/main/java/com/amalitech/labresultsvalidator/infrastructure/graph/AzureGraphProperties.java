package com.amalitech.labresultsvalidator.infrastructure.graph;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "azure.graph")
public record AzureGraphProperties(
    String tenantId,
    String clientId,
    String clientSecret,
    String sanctionedSiteId
) {}
