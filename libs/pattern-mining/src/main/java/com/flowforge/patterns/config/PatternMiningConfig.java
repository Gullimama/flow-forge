package com.flowforge.patterns.config;

import com.flowforge.patterns.mining.SequencePatternMiner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PatternMiningConfig {

    @Bean
    public SequencePatternMiner sequencePatternMiner(
            @Value("${flowforge.pattern-mining.min-support:0.05}") double minSupport,
            @Value("${flowforge.pattern-mining.max-pattern-length:10}") int maxPatternLength) {
        return new SequencePatternMiner(minSupport, maxPatternLength);
    }
}
