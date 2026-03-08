package com.flowforge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import com.flowforge.common.config.FlowForgeProperties;

@SpringBootApplication(scanBasePackages = "com.flowforge")
@EnableConfigurationProperties(FlowForgeProperties.class)
@EntityScan("com.flowforge.common.entity")
@EnableJpaRepositories("com.flowforge.common.repository")
public class FlowForgeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowForgeApiApplication.class, args);
    }
}
