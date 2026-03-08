package com.flowforge.topology.parser;

import com.flowforge.topology.model.ContainerPort;
import com.flowforge.topology.model.EnvVar;
import com.flowforge.topology.model.IngressRule;
import com.flowforge.topology.model.ResourceLimits;
import com.flowforge.topology.model.TopologyNode;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesList;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parse Kubernetes manifests (Deployment, Service, Ingress, ConfigMap) using Fabric8 offline client.
 */
@Component
public class KubernetesManifestParser {

    private static final Logger log = LoggerFactory.getLogger(KubernetesManifestParser.class);

    public KubernetesManifestParser() {
    }

    public List<TopologyNode> parseManifests(Path manifestDir) {
        List<TopologyNode> nodes = new ArrayList<>();
        if (!Files.isDirectory(manifestDir)) {
            return nodes;
        }
        try (var stream = Files.walk(manifestDir)) {
            stream.filter(this::isYamlFile)
                .filter(Files::isRegularFile)
                .forEach(file -> {
                    try (InputStream in = Files.newInputStream(file)) {
                        Object loaded = Serialization.unmarshal(in);
                        List<HasMetadata> resources = toResourceList(loaded);
                        for (HasMetadata resource : resources) {
                            parseResource(resource).ifPresent(nodes::add);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to parse manifest: {}", file, e);
                    }
                });
        } catch (Exception e) {
            log.warn("Failed to walk manifest dir: {}", manifestDir, e);
        }
        return nodes;
    }

    private Optional<TopologyNode> parseResource(HasMetadata resource) {
        if (resource instanceof Deployment d) {
            return Optional.ofNullable(parseDeployment(d));
        }
        if (resource instanceof Service s) {
            return Optional.of(parseService(s));
        }
        if (resource instanceof Ingress i) {
            return Optional.of(parseIngress(i));
        }
        if (resource instanceof ConfigMap c) {
            return Optional.of(parseConfigMap(c));
        }
        return Optional.empty();
    }

    private TopologyNode.ServiceNode parseDeployment(Deployment deployment) {
        var spec = deployment.getSpec();
        if (spec == null || spec.getTemplate() == null || spec.getTemplate().getSpec() == null) {
            return null;
        }
        var podSpec = spec.getTemplate().getSpec();
        if (podSpec.getContainers() == null || podSpec.getContainers().isEmpty()) {
            return null;
        }
        Container container = podSpec.getContainers().get(0);
        var meta = deployment.getMetadata();
        if (meta == null) return null;
        String name = meta.getName();
        String namespace = meta.getNamespace() != null ? meta.getNamespace() : "default";
        var ports = container.getPorts() != null ? container.getPorts() : List.<io.fabric8.kubernetes.api.model.ContainerPort>of();
        List<ContainerPort> mappedPorts = ports.stream()
            .map(p -> new ContainerPort(
                p.getName(),
                p.getContainerPort() != null ? p.getContainerPort() : 0,
                p.getProtocol() != null ? p.getProtocol() : "TCP"))
            .toList();
        return new TopologyNode.ServiceNode(
            "svc:" + name,
            name,
            namespace,
            container.getImage() != null ? container.getImage() : "",
            spec.getReplicas() != null ? spec.getReplicas() : 1,
            meta.getLabels() != null ? meta.getLabels() : Map.of(),
            mappedPorts,
            extractResources(container),
            extractEnvVars(container)
        );
    }

    private TopologyNode.ServiceNode parseService(Service service) {
        var meta = service.getMetadata();
        if (meta == null) return null;
        String name = meta.getName();
        String namespace = meta.getNamespace() != null ? meta.getNamespace() : "default";
        var spec = service.getSpec();
        List<ContainerPort> ports = List.of();
        if (spec != null && spec.getPorts() != null) {
            ports = spec.getPorts().stream()
                .map((ServicePort p) -> new ContainerPort(
                    p.getName(),
                    p.getPort() != null ? p.getPort() : 0,
                    p.getProtocol() != null ? p.getProtocol() : "TCP"))
                .toList();
        }
        return new TopologyNode.ServiceNode(
            "svc:" + name,
            name,
            namespace,
            "",
            0,
            meta.getLabels() != null ? meta.getLabels() : Map.of(),
            ports,
            new ResourceLimits("", "", "", ""),
            List.of()
        );
    }

    private TopologyNode.IngressNode parseIngress(Ingress ingress) {
        var meta = ingress.getMetadata();
        String name = meta != null ? meta.getName() : "ingress";
        var spec = ingress.getSpec();
        List<IngressRule> rules = new ArrayList<>();
        if (spec != null && spec.getRules() != null) {
            for (var rule : spec.getRules()) {
                String host = rule.getHost() != null ? rule.getHost() : "";
                if (rule.getHttp() != null && rule.getHttp().getPaths() != null) {
                    for (var path : rule.getHttp().getPaths()) {
                        String pathVal = path.getPath() != null ? path.getPath() : "/";
                        String svcName = path.getBackend() != null && path.getBackend().getService() != null
                            ? path.getBackend().getService().getName() : "";
                        int port = path.getBackend() != null && path.getBackend().getService() != null
                            && path.getBackend().getService().getPort() != null
                            ? path.getBackend().getService().getPort().getNumber() : 80;
                        rules.add(new IngressRule(host, pathVal, svcName, port));
                    }
                } else {
                    rules.add(new IngressRule(host, "/", "", 80));
                }
            }
        }
        return new TopologyNode.IngressNode("ingress:" + name, name, rules);
    }

    private TopologyNode.ConfigMapNode parseConfigMap(ConfigMap configMap) {
        var meta = configMap.getMetadata();
        String name = meta != null ? meta.getName() : "configmap";
        Map<String, String> data = configMap.getData() != null ? configMap.getData() : Map.of();
        return new TopologyNode.ConfigMapNode("cm:" + name, name, data);
    }

    private ResourceLimits extractResources(Container container) {
        var res = container.getResources();
        if (res == null) return new ResourceLimits("", "", "", "");
        String cpuReq = res.getRequests() != null && res.getRequests().get("cpu") != null
            ? res.getRequests().get("cpu").getAmount() : "";
        String cpuLim = res.getLimits() != null && res.getLimits().get("cpu") != null
            ? res.getLimits().get("cpu").getAmount() : "";
        String memReq = res.getRequests() != null && res.getRequests().get("memory") != null
            ? res.getRequests().get("memory").getAmount() : "";
        String memLim = res.getLimits() != null && res.getLimits().get("memory") != null
            ? res.getLimits().get("memory").getAmount() : "";
        return new ResourceLimits(cpuReq, cpuLim, memReq, memLim);
    }

    private List<EnvVar> extractEnvVars(Container container) {
        if (container.getEnv() == null) return List.of();
        List<EnvVar> result = new ArrayList<>();
        for (var env : container.getEnv()) {
            String secretRef = null;
            if (env.getValueFrom() != null && env.getValueFrom().getSecretKeyRef() != null) {
                var ref = env.getValueFrom().getSecretKeyRef();
                secretRef = ref.getName() + "/" + ref.getKey();
            }
            result.add(new EnvVar(
                env.getName(),
                env.getValue() != null ? env.getValue() : "",
                Optional.ofNullable(secretRef)
            ));
        }
        return result;
    }

    private List<HasMetadata> toResourceList(Object loaded) {
        if (loaded instanceof KubernetesList list) {
            return list.getItems();
        }
        if (loaded instanceof HasMetadata h) {
            return List.of(h);
        }
        return List.of();
    }

    private boolean isYamlFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }
}
