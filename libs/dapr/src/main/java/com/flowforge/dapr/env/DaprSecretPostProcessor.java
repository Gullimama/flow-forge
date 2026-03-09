package com.flowforge.dapr.env;

import io.dapr.client.DaprClient;
import io.dapr.client.DaprClientBuilder;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Loads secrets from Dapr secret store into Spring Environment when profile "dapr" is active.
 * Registered via META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports.
 */
public class DaprSecretPostProcessor implements EnvironmentPostProcessor {

    private static final Map<String, String> SECRET_TO_PROPERTY = Map.of(
        "postgres-password", "spring.datasource.password",
        "minio-secret-key", "flowforge.minio.secret-key",
        "neo4j-password", "flowforge.neo4j.password"
    );

    @Override
    public void postProcessEnvironment(
        ConfigurableEnvironment environment,
        SpringApplication application
    ) {
        if (!environment.acceptsProfiles(Profiles.of("dapr"))) {
            return;
        }
        try {
            DaprClient daprClient = new DaprClientBuilder().build();
            String secretStore = environment.getProperty(
                "flowforge.dapr.secret-store-name",
                "flowforge-secrets"
            );

            var properties = new java.util.Properties();
            for (var entry : SECRET_TO_PROPERTY.entrySet()) {
                try {
                    var secrets = daprClient.getSecret(secretStore, entry.getKey())
                        .block(Duration.ofSeconds(10));
                    if (secrets != null && secrets.containsKey(entry.getKey())) {
                        properties.put(entry.getValue(), secrets.get(entry.getKey()));
                    }
                } catch (Exception e) {
                    System.err.println("Dapr secret '%s' unavailable: %s"
                        .formatted(entry.getKey(), e.getMessage()));
                }
            }

            if (!properties.isEmpty()) {
                environment.getPropertySources().addFirst(
                    new org.springframework.core.env.PropertiesPropertySource(
                        "daprSecrets", properties));
            }
            daprClient.close();
        } catch (Exception e) {
            System.err.println("Dapr secret loading skipped: " + e.getMessage());
        }
    }
}

