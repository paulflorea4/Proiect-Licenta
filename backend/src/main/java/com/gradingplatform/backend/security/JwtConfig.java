package com.gradingplatform.backend.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link JwtProperties} so it is bound and validated at startup. */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {}
