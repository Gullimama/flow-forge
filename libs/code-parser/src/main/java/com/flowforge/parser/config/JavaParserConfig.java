package com.flowforge.parser.config;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JavaParserConfig {

    @Bean
    public ParserConfiguration parserConfiguration(
            @Value("${flowforge.parser.language-level:JAVA_11}") String languageLevel) {
        var config = new ParserConfiguration();
        try {
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.valueOf(languageLevel));
        } catch (IllegalArgumentException e) {
            config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_11);
        }
        config.setAttributeComments(true);
        config.setDoNotAssignCommentsPrecedingEmptyLines(false);
        return config;
    }

    @Bean
    public JavaParser javaParser(ParserConfiguration config) {
        return new JavaParser(config);
    }
}
