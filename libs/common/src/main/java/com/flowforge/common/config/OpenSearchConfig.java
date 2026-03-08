package com.flowforge.common.config;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.apache.http.HttpHost;
import org.opensearch.client.RestClient;
import org.opensearch.client.RestClientBuilder;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Configures OpenSearch RestClient and OpenSearchClient from flowforge.opensearch.*.
 */
@Configuration
@ConditionalOnProperty(prefix = "flowforge.opensearch", name = "hosts")
public class OpenSearchConfig {

    @Bean
    public RestClient openSearchRestClient(FlowForgeProperties props) throws IOException {
        List<String> hosts = props.opensearch().hosts();
        if (hosts == null || hosts.isEmpty()) {
            throw new IllegalStateException("flowforge.opensearch.hosts must be set");
        }
        HttpHost[] httpHosts = hosts.stream()
            .map(OpenSearchConfig::parseHost)
            .toArray(HttpHost[]::new);

        RestClientBuilder builder = RestClient.builder(httpHosts);
        if (StringUtils.hasText(props.opensearch().username()) && StringUtils.hasText(props.opensearch().password())) {
            String auth = props.opensearch().username() + ":" + props.opensearch().password();
            byte[] encoded = java.util.Base64.getEncoder().encode(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new org.apache.http.Header[] {
                new org.apache.http.message.BasicHeader("Authorization", "Basic " + new String(encoded, java.nio.charset.StandardCharsets.UTF_8))
            });
        }
        return builder.build();
    }

    private static HttpHost parseHost(String hostUrl) {
        try {
            URI uri = URI.create(hostUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 9200);
            String scheme = uri.getScheme() != null ? uri.getScheme() : "http";
            return new HttpHost(uri.getHost(), port, scheme);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OpenSearch host: " + hostUrl, e);
        }
    }

    @Bean
    public OpenSearchClient openSearchClient(RestClient restClient) {
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new OpenSearchClient(transport);
    }
}
