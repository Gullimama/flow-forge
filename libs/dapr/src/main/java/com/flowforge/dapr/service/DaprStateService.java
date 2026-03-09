package com.flowforge.dapr.service;

import com.flowforge.dapr.config.DaprProperties;
import io.dapr.client.DaprClient;
import io.dapr.client.domain.State;
import io.dapr.client.domain.StateOptions;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class DaprStateService {

    private final DaprClient daprClient;
    private final DaprProperties props;

    public DaprStateService(DaprClient daprClient, DaprProperties props) {
        this.daprClient = daprClient;
        this.props = props;
    }

    public void saveState(String key, Object value) {
        daprClient.saveState(props.stateStoreName(), key, value)
            .block(props.timeout());
    }

    public void saveState(String key, Object value, String etag) {
        var stateOptions = new StateOptions(
            StateOptions.Consistency.STRONG,
            StateOptions.Concurrency.FIRST_WRITE
        );
        daprClient.saveState(props.stateStoreName(), key, etag, value, stateOptions)
            .block(props.timeout());
    }

    public <T> Optional<T> getState(String key, Class<T> type) {
        var state = daprClient.getState(props.stateStoreName(), key, type)
            .block(props.timeout());
        return Optional.ofNullable(state).map(State::getValue);
    }

    public void deleteState(String key) {
        daprClient.deleteState(props.stateStoreName(), key)
            .block(props.timeout());
    }

    public void saveBulkState(Map<String, Object> states) {
        var stateList = states.entrySet().stream()
            .map(e -> new State<Object>(e.getKey(), e.getValue(), ""))
            .toList();
        daprClient.saveBulkState(props.stateStoreName(), (List<State<?>>) (List<?>) stateList)
            .block(props.timeout());
    }
}

