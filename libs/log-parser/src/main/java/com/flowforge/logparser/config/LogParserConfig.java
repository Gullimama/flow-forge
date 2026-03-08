package com.flowforge.logparser.config;

import com.flowforge.logparser.drain.DrainParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LogParserConfig {

    @Bean
    public DrainParser drainParser(
        @Value("${flowforge.drain.similarity-threshold:0.5}") double similarityThreshold,
        @Value("${flowforge.drain.max-depth:4}") int maxDepth,
        @Value("${flowforge.drain.max-children:100}") int maxChildren
    ) {
        return new DrainParser(similarityThreshold, maxDepth, maxChildren);
    }
}
