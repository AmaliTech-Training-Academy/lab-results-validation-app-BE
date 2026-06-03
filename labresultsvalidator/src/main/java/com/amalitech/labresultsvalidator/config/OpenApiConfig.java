package com.amalitech.labresultsvalidator.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Value("${server.port:8080}")
    private String serverPort;

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .servers(servers())
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, jwtSecurityScheme()));
    }

    private Info apiInfo() {
        return new Info()
                .title("Lab Results Validation API")
                .description("""
                        REST API for ingesting, validating, and persisting lab result data \
                        for learners enrolled in a cohort-based training programme.

                        Authenticated endpoints require a Bearer JWT token obtained from \
                        POST /api/v1/auth/login. Paste the token into the Authorize dialog above.""")
                .version("v1.0.0")
                .contact(new Contact()
                        .name("Amalitech Training")
                        .email("support@amalitechtraining.org"));
    }

    private List<Server> servers() {
        return List.of(
                new Server()
                        .url("http://localhost:" + serverPort)
                        .description("Local development")
        );
    }

    private SecurityScheme jwtSecurityScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the JWT token from POST /api/v1/auth/login (without the 'Bearer ' prefix)");
    }
}
