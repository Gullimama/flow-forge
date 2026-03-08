package com.flowforge.topology.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.topology.model.TopologyEdge;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class MicronautConfigParserTest {

    private final MicronautConfigParser parser = new MicronautConfigParser();

    @Test
    void parseServiceConfig_httpClients_createsHttpCallEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service", dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.HTTP_CALL)
            .isNotEmpty();
        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.HTTP_CALL)
            .extracting(TopologyEdge::targetId)
            .contains("svc:payment-service");
    }

    @Test
    void parseServiceConfig_kafkaConsumers_createsConsumeEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service", dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.KAFKA_CONSUME)
            .isNotEmpty();
    }

    @Test
    void parseServiceConfig_datasource_createsDatabaseEdge(@TempDir Path dir) throws Exception {
        copyFixture("sample-application.yml", dir.resolve("application.yml"));

        var edges = parser.parseServiceConfig("booking-service", dir.resolve("application.yml"));

        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.DATABASE_CONNECT)
            .hasSize(1);
        assertThat(edges).filteredOn(e -> e.edgeType() == TopologyEdge.EdgeType.DATABASE_CONNECT)
            .extracting(TopologyEdge::targetId)
            .allMatch(id -> id.startsWith("db:"));
    }

    @Test
    void parseServiceConfig_missingConfig_returnsEmptyEdges(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("application.yml"), "micronaut:\n  application:\n    name: empty-svc\n");

        var edges = parser.parseServiceConfig("empty-svc", dir.resolve("application.yml"));

        assertThat(edges).isEmpty();
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            Files.write(target, in.readAllBytes());
        }
    }
}
