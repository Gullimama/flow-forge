package com.flowforge.common;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.flowforge.common")
@EntityScan("com.flowforge.common.entity")
@EnableJpaRepositories("com.flowforge.common.repository")
public class TestJpaApplication {
}
