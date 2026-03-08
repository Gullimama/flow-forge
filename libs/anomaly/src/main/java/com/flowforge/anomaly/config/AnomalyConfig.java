package com.flowforge.anomaly.config;

import com.flowforge.anomaly.model.AnomalyDetectorModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnomalyConfig {

    @Bean
    public AnomalyDetectorModel anomalyDetectorModel(
        @Value("${flowforge.anomaly.num-trees:200}") int numTrees,
        @Value("${flowforge.anomaly.subsample-size:256}") int subsampleSize
    ) {
        return new AnomalyDetectorModel(numTrees, subsampleSize);
    }
}
