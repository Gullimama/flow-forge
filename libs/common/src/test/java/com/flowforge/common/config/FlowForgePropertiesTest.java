package com.flowforge.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = FlowForgePropertiesTest.TestConfig.class)
@ActiveProfiles("test")
@Tag("unit")
class FlowForgePropertiesTest {

    @EnableConfigurationProperties(FlowForgeProperties.class)
    @Configuration
    static class TestConfig {}

    @Autowired
    private FlowForgeProperties props;

    @Test
    void minioPropertiesBoundFromYaml() {
        assertThat(props.minio()).isNotNull();
        assertThat(props.minio().endpoint()).isEqualTo("http://localhost:9000");
        assertThat(props.minio().accessKey()).isNotBlank();
    }

    @Test
    void opensearchPropertiesBoundFromYaml() {
        assertThat(props.opensearch()).isNotNull();
        assertThat(props.opensearch().hosts()).isNotEmpty();
    }

    @Test
    void allNestedRecordSectionsPopulated() {
        assertAll(
            () -> assertThat(props.minio()).isNotNull(),
            () -> assertThat(props.azure()).isNotNull(),
            () -> assertThat(props.opensearch()).isNotNull(),
            () -> assertThat(props.qdrant()).isNotNull(),
            () -> assertThat(props.neo4j()).isNotNull(),
            () -> assertThat(props.postgres()).isNotNull(),
            () -> assertThat(props.vllm()).isNotNull(),
            () -> assertThat(props.tei()).isNotNull(),
            () -> assertThat(props.gnn()).isNotNull()
        );
    }
}
