package com.flowforge.publisher.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FreeMarker configuration for research document templates.
 */
@Configuration
public class FreeMarkerConfig {

    @Bean
    public freemarker.template.Configuration freeMarkerConfiguration() {
        var config = new freemarker.template.Configuration(
            freemarker.template.Configuration.VERSION_2_3_33);
        config.setObjectWrapper(new freemarker.template.DefaultObjectWrapper(
            freemarker.template.Configuration.VERSION_2_3_33));
        config.setClassLoaderForTemplateLoading(
            getClass().getClassLoader(), "templates");
        config.setDefaultEncoding("UTF-8");
        config.setOutputEncoding("UTF-8");
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        return config;
    }
}
