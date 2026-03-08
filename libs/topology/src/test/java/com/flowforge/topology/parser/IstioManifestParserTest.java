package com.flowforge.topology.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.topology.model.TopologyEdge;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class IstioManifestParserTest {

    private final IstioManifestParser parser = new IstioManifestParser();

    @Test
    void parseIstioManifests_virtualService_createsServiceEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice.yaml", dir.resolve("vs.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).isNotEmpty();
        assertThat(edges).allMatch(e -> e.edgeType() == TopologyEdge.EdgeType.SERVICE_DEPENDENCY);
        assertThat(edges).extracting(e -> e.metadata().get("source"))
            .contains("istio-virtualservice");
    }

    @Test
    void parseIstioManifests_virtualServiceWithWeights_capturesWeight(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice-canary.yaml", dir.resolve("vs-canary.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).anyMatch(e -> e.metadata().containsKey("weight"));
    }

    @Test
    void parseIstioManifests_destinationRule_createsEdge(@TempDir Path dir) throws Exception {
        copyFixture("sample-destinationrule.yaml", dir.resolve("dr.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).metadata().get("source")).isEqualTo("istio-destinationrule");
    }

    @Test
    void parseIstioManifests_gateway_createsIngressRouteEdges(@TempDir Path dir) throws Exception {
        copyFixture("sample-gateway.yaml", dir.resolve("gw.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges).isNotEmpty();
        assertThat(edges).allMatch(e -> e.edgeType() == TopologyEdge.EdgeType.INGRESS_ROUTE);
        assertThat(edges).extracting(TopologyEdge::targetId)
            .allMatch(id -> id.startsWith("host:"));
    }

    @Test
    void parseIstioManifests_mixedManifests_parsesAll(@TempDir Path dir) throws Exception {
        copyFixture("sample-virtualservice.yaml", dir.resolve("vs.yaml"));
        copyFixture("sample-destinationrule.yaml", dir.resolve("dr.yaml"));
        copyFixture("sample-gateway.yaml", dir.resolve("gw.yaml"));

        var edges = parser.parseIstioManifests(dir);

        assertThat(edges.size()).isGreaterThanOrEqualTo(3);
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            java.nio.file.Files.write(target, in.readAllBytes());
        }
    }
}
