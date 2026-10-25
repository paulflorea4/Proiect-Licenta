package com.gradingplatform.backend.config;

import com.gradingplatform.backend.ai.AiServiceClient;
import com.gradingplatform.backend.ai.AiServiceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AiServiceProperties} (bound and checked at startup) and the one {@link AiServiceClient}. */
@Configuration
@EnableConfigurationProperties(AiServiceProperties.class)
public class AiServiceConfig {

    @Bean
    AiServiceClient aiServiceClient(AiServiceProperties properties) {
        return new AiServiceClient(properties);
    }
}
