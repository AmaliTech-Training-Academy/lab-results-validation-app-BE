package com.amalitech.labresultsvalidator.infrastructure.graph;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.microsoft.graph.core.authentication.AzureIdentityAuthenticationProvider;
import com.microsoft.graph.core.requests.GraphClientFactory;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.kiota.authentication.AuthenticationProvider;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
    AzureGraphProperties.class,
    SharePointProperties.class,
    GraphRetryProperties.class,
    FixtureDriveProperties.class
})
public class GraphConfig {

    /** Real clock sleeping; retry tests substitute a recording implementation. */
    @Bean
    public Sleeper sleeper() {
        return Sleeper.real();
    }

    /**
     * Built only when the real drive is in use. In fixture mode there are no Azure credentials to
     * build a credential from, so constructing this bean would fail startup for a run that never
     * intends to call Graph.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "validata.sharepoint", name = "source", havingValue = "graph", matchIfMissing = true)
    public GraphServiceClient graphServiceClient(AzureGraphProperties props) {
        ClientSecretCredential credential = new ClientSecretCredentialBuilder()
            .tenantId(props.tenantId())
            .clientId(props.clientId())
            .clientSecret(props.clientSecret())
            .build();

        AuthenticationProvider authProvider = new AzureIdentityAuthenticationProvider(
            credential, new String[] {}, "https://graph.microsoft.com/.default");

        // The SDK's default OkHttpClient has no explicit timeouts, so a stalled SharePoint response
        // (a large workbook download over a flaky network segment) can block a standupTaskExecutor/
        // syncTaskExecutor thread indefinitely — a hang that never throws is never retried by
        // GraphRetryExecutor either, since that only reacts to a thrown exception. Building on top of
        // GraphClientFactory.create() keeps the SDK's own default interceptors (retry-on-CAE,
        // telemetry); only the timeouts are added.
        OkHttpClient httpClient = GraphClientFactory.create()
            .connectTimeout(Duration.ofSeconds(15))
            .readTimeout(Duration.ofSeconds(60))
            .writeTimeout(Duration.ofSeconds(60))
            .callTimeout(Duration.ofSeconds(90))
            .build();

        return new GraphServiceClient(authProvider, httpClient);
    }
}
