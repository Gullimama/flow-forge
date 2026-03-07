package com.flowforge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.flowforge")
@ConfigurationPropertiesScan("com.flowforge.common.config")
public class FlowForgeApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowForgeApiApplication.class, args);
    }
}
