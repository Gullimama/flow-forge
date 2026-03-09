package com.flowforge.dapr.service;

import com.flowforge.dapr.config.DaprProperties;
import io.dapr.client.DaprClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DaprSecretService {

    private static final Logger log = LoggerFactory.getLogger(DaprSecretService.class);

    private final DaprClient daprClient;
    private final DaprProperties props;

    public DaprSecretService(DaprClient daprClient, DaprProperties props) {
        this.daprClient = daprClient;
        this.props = props;
    }

    public String getSecret(String secretName) {
        Map<String, String> secrets = daprClient.getSecret(props.secretStoreName(), secretName)
            .block(props.timeout());
        if (secrets == null || !secrets.containsKey(secretName)) {
            throw new IllegalStateException(
                "Secret '%s' not found in store '%s'"
                    .formatted(secretName, props.secretStoreName()));
        }
        return secrets.get(secretName);
    }

    public Map<String, String> getSecrets(List<String> secretNames) {
        return secretNames.stream()
            .collect(Collectors.toMap(name -> name, this::getSecret));
    }

    public Map<String, Map<String, String>> getBulkSecret() {
        return daprClient.getBulkSecret(props.secretStoreName())
            .block(props.timeout());
    }
}

