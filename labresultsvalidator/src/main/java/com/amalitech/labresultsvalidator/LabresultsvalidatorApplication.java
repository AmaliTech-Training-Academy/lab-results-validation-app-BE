package com.amalitech.labresultsvalidator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the Lab Results Validator application. */
@SpringBootApplication(proxyBeanMethods = false)
public final class LabresultsvalidatorApplication {

    private LabresultsvalidatorApplication() {
    }

    /**
     * Starts the Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(LabresultsvalidatorApplication.class, args);
    }

}
