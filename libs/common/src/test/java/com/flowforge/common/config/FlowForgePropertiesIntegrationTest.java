package com.flowforge.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = FlowForgePropertiesIntegrationTest.TestConfig.class)
@ActiveProfiles("test")
@Tag("integration")
class FlowForgePropertiesIntegrationTest {

    @EnableConfigurationProperties(FlowForgeProperties.class)
    @Configuration
    static class TestConfig {}

    @Autowired
    private ApplicationContext context;

    @Test
    void flowForgePropertiesBeanExistsInContext() {
        assertThat(context.getBean(FlowForgeProperties.class)).isNotNull();
    }
}
