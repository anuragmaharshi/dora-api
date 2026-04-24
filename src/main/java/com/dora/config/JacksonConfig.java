package com.dora.config;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    // Disable WRITE_DATES_AS_TIMESTAMPS so Instant fields serialize as ISO-8601 strings,
    // matching the openapi.yaml contract (format: date-time).
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
