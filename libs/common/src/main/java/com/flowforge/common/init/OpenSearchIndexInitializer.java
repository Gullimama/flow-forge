package com.flowforge.common.init;

import com.flowforge.common.client.OpenSearchClientWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * On application startup, ensures the four OpenSearch indexes exist using schemas from classpath.
 */
@Component
@ConditionalOnBean(OpenSearchClientWrapper.class)
public class OpenSearchIndexInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OpenSearchIndexInitializer.class);

    private static final String OPENSEARCH_DIR = "opensearch/";
    private static final String[] INDEX_NAMES = {
        "code-artifacts",
        "config-artifacts",
        "runtime-events",
        "anomaly-episodes"
    };

    private final OpenSearchClientWrapper client;

    public OpenSearchIndexInitializer(OpenSearchClientWrapper client) {
        this.client = client;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String indexName : INDEX_NAMES) {
            String schema = loadSchema(OPENSEARCH_DIR + indexName + ".json");
            if (schema == null) {
                log.warn("OpenSearch schema not found for index: {}", indexName);
                continue;
            }
            try {
                client.ensureIndex(indexName, schema);
                log.debug("OpenSearch index ensured: {}", indexName);
            } catch (Exception e) {
                log.error("Failed to ensure OpenSearch index {}: {}", indexName, e.getMessage());
            }
        }
    }

    private String loadSchema(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }
}
