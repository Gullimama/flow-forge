package com.flowforge.common.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowforge.common.config.FlowForgeProperties;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.client.RestClient;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.core.CountRequest;
import org.opensearch.client.json.JsonData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around OpenSearch client: ensure index, bulk index, search, delete by query, count, health.
 */
@Component
@ConditionalOnBean(OpenSearchClient.class)
public class OpenSearchClientWrapper {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchClientWrapper.class);

    private final RestClient restClient;
    private final OpenSearchClient client;
    private final FlowForgeProperties props;
    private final ObjectMapper objectMapper;

    public OpenSearchClientWrapper(RestClient restClient, OpenSearchClient client, FlowForgeProperties props) {
        this.restClient = restClient;
        this.client = client;
        this.props = props;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Create or update an index with the given settings and mappings JSON (full index body).
     * Idempotent: if index already exists, the call is a no-op.
     */
    public void ensureIndex(String indexName, String settingsJson) throws IOException {
        String resolved = resolveIndexName(indexName);
        Request request = new Request("PUT", "/" + resolved);
        request.setJsonEntity(settingsJson);
        try {
            restClient.performRequest(request);
        } catch (org.opensearch.client.ResponseException e) {
            int code = e.getResponse().getStatusLine().getStatusCode();
            if (code == 400) {
                String body = "";
                if (e.getResponse().getEntity() != null) {
                    try (java.io.InputStream in = e.getResponse().getEntity().getContent()) {
                        body = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                if (body.contains("resource_already_exists_exception")) {
                    return; // idempotent
                }
            }
            throw e;
        }
    }

    /**
     * Bulk index documents. Returns a simple result with errors flag.
     */
    public BulkResult bulkIndex(String indexName, List<Map<String, Object>> documents) throws IOException {
        String resolved = resolveIndexName(indexName);
        List<BulkOperation> ops = documents.stream()
            .map(doc -> {
                try {
                    String json = objectMapper.writeValueAsString(doc);
                    return BulkOperation.of(b -> b.index(IndexOperation.of(i -> i.index(resolved).document(JsonData.of(json)))));
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new RuntimeException(e);
                }
            })
            .toList();
        BulkRequest bulkRequest = BulkRequest.of(r -> r.index(resolved).operations(ops));
        BulkResponse response = client.bulk(bulkRequest);
        return new BulkResult(response.errors());
    }

    /**
     * Search with a query DSL map (e.g. Map.of("query", Map.of("match_all", Map.of()))).
     */
    @SuppressWarnings("unchecked")
    public SearchResult search(String indexName, Map<String, Object> query, int size) throws IOException {
        String resolved = resolveIndexName(indexName);
        Map<String, Object> body = new java.util.HashMap<>(query);
        body.put("size", size);
        String bodyJson = objectMapper.writeValueAsString(body);
        Request request = new Request("POST", "/" + resolved + "/_search");
        request.setJsonEntity(bodyJson);
        Response response = restClient.performRequest(request);
        String responseBody = response.getEntity() != null
            ? new String(response.getEntity().getContent().readAllBytes())
            : "{}";
        Map<String, Object> parsed = objectMapper.readValue(responseBody, Map.class);
        Map<String, Object> hitsMap = (Map<String, Object>) parsed.getOrDefault("hits", Map.of());
        List<Map<String, Object>> hitList = (List<Map<String, Object>>) hitsMap.getOrDefault("hits", List.of());
        List<SearchHit> hits = hitList.stream()
            .map(h -> (Map<String, Object>) h.getOrDefault("_source", Map.of()))
            .map(SearchHit::new)
            .toList();
        return new SearchResult(hits);
    }

    /**
     * Multi-match search across the given fields. Returns up to topK hits.
     */
    public List<SearchHit> multiMatchSearch(String indexName, String queryText, List<String> fields, int topK) throws IOException {
        Map<String, Object> query = Map.of(
            "query", Map.of(
                "multi_match", Map.of(
                    "query", queryText,
                    "fields", fields
                )
            )
        );
        return search(indexName, query, topK).hits();
    }

    /**
     * Delete documents matching the query (e.g. Map.of("term", Map.of("snapshot_id", "snap-1"))).
     */
    public void deleteByQuery(String indexName, Map<String, Object> query) throws IOException {
        String resolved = resolveIndexName(indexName);
        String bodyJson = objectMapper.writeValueAsString(Map.of("query", query));
        Request request = new Request("POST", "/" + resolved + "/_delete_by_query");
        request.setJsonEntity(bodyJson);
        restClient.performRequest(request);
    }

    /**
     * Get document count for the index.
     */
    public long getDocCount(String indexName) throws IOException {
        String resolved = resolveIndexName(indexName);
        return client.count(CountRequest.of(c -> c.index(resolved))).count();
    }

    /**
     * Refresh the index so that recently indexed documents are visible to search. Call after bulk index in tests.
     */
    public void refreshIndex(String indexName) throws IOException {
        String resolved = resolveIndexName(indexName);
        Request request = new Request("POST", "/" + resolved + "/_refresh");
        restClient.performRequest(request);
    }

    /**
     * Health check: cluster is reachable and not red.
     */
    public boolean healthCheck() {
        try {
            Request request = new Request("GET", "/_cluster/health");
            Response response = restClient.performRequest(request);
            int status = response.getStatusLine().getStatusCode();
            if (status != 200) {
                return false;
            }
            String body = response.getEntity() != null
                ? new String(response.getEntity().getContent().readAllBytes())
                : "{}";
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            Object statusObj = map.get("status");
            return statusObj != null && !"red".equals(statusObj.toString());
        } catch (Exception e) {
            log.debug("OpenSearch health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String resolveIndexName(String indexName) {
        String prefix = props != null && props.opensearch() != null && props.opensearch().indexPrefix() != null
            ? props.opensearch().indexPrefix().strip()
            : "";
        if (prefix.isEmpty()) {
            return indexName;
        }
        return prefix + "-" + indexName;
    }

    public record BulkResult(boolean errors) {}

    public record SearchHit(Map<String, Object> sourceAsMap) {
        public Map<String, Object> getSourceAsMap() {
            return sourceAsMap != null ? sourceAsMap : Map.of();
        }
    }

    public record SearchResult(List<SearchHit> hits) {}
}
