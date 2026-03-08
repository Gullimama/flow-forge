package com.flowforge.topology;

import com.flowforge.common.config.FlowForgeProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * Minimal Spring context for topology integration tests: OpenSearch + MinIO + topology beans, no JPA.
 */
@SpringBootApplication(excludeName = {
    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
})
@ComponentScan(basePackages = { "com.flowforge.topology", "com.flowforge.common.config", "com.flowforge.common.client" })
@EnableConfigurationProperties(FlowForgeProperties.class)
public class TestTopologyApplication {
}
