package com.flowforge.topology.model;

import java.util.List;
import java.util.Map;

public sealed interface TopologyNode {
    String id();
    String name();
    String nodeType();

    record ServiceNode(
        String id,
        String name,
        String namespace,
        String image,
        int replicas,
        Map<String, String> labels,
        List<ContainerPort> ports,
        ResourceLimits resources,
        List<EnvVar> envVars
    ) implements TopologyNode {
        @Override
        public String nodeType() { return "SERVICE"; }
    }

    record IngressNode(
        String id,
        String name,
        List<IngressRule> rules
    ) implements TopologyNode {
        @Override
        public String nodeType() { return "INGRESS"; }
    }

    record KafkaTopicNode(
        String id,
        String name,
        int partitions,
        int replicationFactor
    ) implements TopologyNode {
        @Override
        public String nodeType() { return "KAFKA_TOPIC"; }
    }

    record DatabaseNode(
        String id,
        String name,
        String dbType,
        String host,
        int port
    ) implements TopologyNode {
        @Override
        public String nodeType() { return "DATABASE"; }
    }

    record ConfigMapNode(
        String id,
        String name,
        Map<String, String> data
    ) implements TopologyNode {
        @Override
        public String nodeType() { return "CONFIG_MAP"; }
    }
}
