package com.flowforge.graph.config;

import com.flowforge.common.config.FlowForgeProperties;
import java.util.concurrent.TimeUnit;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class Neo4jConfig {

    @Bean
    public Driver neo4jDriver(FlowForgeProperties props) {
        var neo4j = props.neo4j();
        if (neo4j == null || neo4j.uri() == null || neo4j.uri().isBlank()) {
            throw new IllegalStateException("flowforge.neo4j.uri is required");
        }
        return GraphDatabase.driver(
            neo4j.uri(),
            AuthTokens.basic(neo4j.user() != null ? neo4j.user() : "neo4j", neo4j.password() != null ? neo4j.password() : ""),
            Config.builder()
                .withMaxConnectionPoolSize(50)
                .withConnectionAcquisitionTimeout(30, TimeUnit.SECONDS)
                .build()
        );
    }
}
