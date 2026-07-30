package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({
    AzureGraphProperties.class,
    SharePointProperties.class,
    GraphRetryProperties.class
})
public class GraphConfig {

    /** Real clock sleeping; retry tests substitute a recording implementation. */
    @Bean
    public Sleeper sleeper() {
        return Sleeper.real();
    }

    @Bean
    public GraphServiceClient graphServiceClient(AzureGraphProperties props) {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
            .tenantId(props.tenantId())
            .clientId(props.clientId())
            .clientSecret(props.clientSecret())
            .build();
        return new GraphServiceClient(credential, "https://graph.microsoft.com/.default");
    }
}
