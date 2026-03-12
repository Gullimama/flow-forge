package com.flowforge.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flowforge")
public record FlowForgeProperties(
    MinioProperties minio,
    AzureProperties azure,
    OpenSearchProperties opensearch,
    QdrantProperties qdrant,
    Neo4jProperties neo4j,
    PostgresProperties postgres,
    VllmProperties vllm,
    EmbeddingProperties embedding,
    TeiProperties tei,
    OllamaProperties ollama,
    GnnProperties gnn
) {
    /** Default embedding provider when not set: tei (AKS), ollama (local macOS), openai (optional). */
    public String embeddingProvider() {
        return embedding != null && embedding.provider() != null && !embedding.provider().isBlank()
            ? embedding.provider().trim().toLowerCase()
            : "tei";
    }

    public record MinioProperties(boolean enabled, String endpoint, String accessKey, String secretKey, boolean secure) {}
    public record AzureProperties(boolean enabled, String connectionString) {}
    public record OpenSearchProperties(List<String> hosts, String username, String password, String indexPrefix) {}
    public record QdrantProperties(String host, int port, String apiKey, String collectionPrefix) {}
    public record Neo4jProperties(String uri, String user, String password, String database) {}
    public record PostgresProperties(String url, String username, String password) {}
    public record VllmProperties(String baseUrl, String apiKey, String model) {}
    public record EmbeddingProperties(String provider) {}
    public record TeiProperties(String codeUrl, String logUrl, String rerankerUrl) {}
    public record OllamaProperties(String baseUrl, String chatModel, String embeddingModel) {}
    public record GnnProperties(String linkPredictionModelPath, String nodeClassificationModelPath, String classifierModelPath) {}
}
