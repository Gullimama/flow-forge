package com.flowforge.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.flowforge.ingest", "com.flowforge.common"})
@EnableConfigurationProperties(FlowForgeProperties.class)
@EntityScan("com.flowforge.common.entity")
@EnableJpaRepositories("com.flowforge.common.repository")
public class IngestTestApplication {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
