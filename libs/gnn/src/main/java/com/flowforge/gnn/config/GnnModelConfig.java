package com.flowforge.gnn.config;

import ai.djl.repository.zoo.Criteria;
import ai.djl.ndarray.NDList;
import com.flowforge.common.config.FlowForgeProperties;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DJL Criteria for GNN ONNX models (link prediction and node classification).
 */
@Configuration
@ConditionalOnProperty(name = "flowforge.gnn.link-prediction-model-path", matchIfMissing = false)
public class GnnModelConfig {

    @Bean
    public Criteria<NDList, NDList> gnnLinkPredictionCriteria(FlowForgeProperties props) {
        return Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Path.of(props.gnn().linkPredictionModelPath()))
            .optEngine("OnnxRuntime")
            .optOption("interOpNumThreads", "4")
            .optOption("intraOpNumThreads", "4")
            .build();
    }

    @Bean
    public Criteria<NDList, NDList> gnnNodeClassificationCriteria(FlowForgeProperties props) {
        return Criteria.builder()
            .setTypes(NDList.class, NDList.class)
            .optModelPath(Path.of(props.gnn().nodeClassificationModelPath()))
            .optEngine("OnnxRuntime")
            .build();
    }
}
