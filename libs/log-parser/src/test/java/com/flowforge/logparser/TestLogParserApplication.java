package com.flowforge.logparser;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring context for log-parser integration tests: OpenSearch + MinIO + log parser beans, no JPA.
 */
@SpringBootApplication(excludeName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@ComponentScan(basePackages = { "com.flowforge.logparser", "com.flowforge.common.config", "com.flowforge.common.client" })
@EnableConfigurationProperties(FlowForgeProperties.class)
public class TestLogParserApplication {
}
