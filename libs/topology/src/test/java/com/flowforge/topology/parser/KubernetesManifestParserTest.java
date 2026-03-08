package com.flowforge.topology.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowforge.topology.model.TopologyNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class KubernetesManifestParserTest {

    private final KubernetesManifestParser parser = new KubernetesManifestParser();

    @Test
    void parseManifests_deployment_extractsServiceNode(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("booking-deployment.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0)).isInstanceOf(TopologyNode.ServiceNode.class);
        var svc = (TopologyNode.ServiceNode) nodes.get(0);
        assertThat(svc.name()).isEqualTo("booking-service");
        assertThat(svc.namespace()).isEqualTo("production");
        assertThat(svc.replicas()).isEqualTo(3);
        assertThat(svc.image()).contains("booking-service");
    }

    @Test
    void parseManifests_deployment_extractsPortsAndResources(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("booking-deployment.yaml"));

        var nodes = parser.parseManifests(manifestDir);
        var svc = (TopologyNode.ServiceNode) nodes.get(0);

        assertThat(svc.ports()).isNotEmpty();
        assertThat(svc.ports().get(0).port()).isEqualTo(8080);
        assertThat(svc.resources().cpuLimit()).isNotBlank();
        assertThat(svc.resources().memLimit()).isNotBlank();
    }

    @Test
    void parseManifests_ingress_extractsRules(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-ingress.yaml", manifestDir.resolve("ingress.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).filteredOn(n -> n instanceof TopologyNode.IngressNode).hasSize(1);
        var ingress = (TopologyNode.IngressNode) nodes.stream()
            .filter(n -> n instanceof TopologyNode.IngressNode)
            .findFirst()
            .orElseThrow();
        assertThat(ingress.rules()).isNotEmpty();
        assertThat(ingress.rules().get(0).host()).isNotBlank();
    }

    @Test
    void parseManifests_configMap_extractsData(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-configmap.yaml", manifestDir.resolve("config.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).filteredOn(n -> n instanceof TopologyNode.ConfigMapNode).hasSize(1);
        var cm = (TopologyNode.ConfigMapNode) nodes.stream()
            .filter(n -> n instanceof TopologyNode.ConfigMapNode)
            .findFirst()
            .orElseThrow();
        assertThat(cm.data()).isNotEmpty();
    }

    @Test
    void parseManifests_sealedInterfaceSwitch_dispatchesCorrectly(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment.yaml", manifestDir.resolve("deploy.yaml"));
        copyFixture("sample-ingress.yaml", manifestDir.resolve("ingress.yaml"));
        copyFixture("sample-configmap.yaml", manifestDir.resolve("cm.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(3);
        assertThat(nodes.stream().map(TopologyNode::nodeType).toList())
            .containsExactlyInAnyOrder("SERVICE", "INGRESS", "CONFIG_MAP");
    }

    @Test
    void parseManifests_envVarsWithSecretRefs_captured(@TempDir Path manifestDir) throws Exception {
        copyFixture("sample-deployment-with-secrets.yaml", manifestDir.resolve("deploy.yaml"));

        var nodes = parser.parseManifests(manifestDir);
        assertThat(nodes).hasSize(1);
        var svc = (TopologyNode.ServiceNode) nodes.get(0);

        assertThat(svc.envVars()).isNotEmpty();
        assertThat(svc.envVars()).anyMatch(env -> env.secretRef().isPresent());
    }

    @Test
    void parseManifests_malformedYaml_skipsWithWarning(@TempDir Path manifestDir) throws Exception {
        Files.writeString(manifestDir.resolve("broken.yaml"), "{{not valid yaml at all");
        copyFixture("sample-deployment.yaml", manifestDir.resolve("good.yaml"));

        var nodes = parser.parseManifests(manifestDir);

        assertThat(nodes).hasSize(1);
    }

    private void copyFixture(String classPath, Path target) throws Exception {
        try (var in = new ClassPathResource(classPath).getInputStream()) {
            Files.write(target, in.readAllBytes());
        }
    }
}
