package com.github.guoyyit.codecoveragejava.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableConfigurationProperties(CoverageProperties.class)
public class CoverageConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer fieldVisibilityCustomizer() {
        return builder -> builder.visibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
    }
}
