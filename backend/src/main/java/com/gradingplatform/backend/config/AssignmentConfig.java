package com.gradingplatform.backend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link AssignmentProperties}. */
@Configuration
@EnableConfigurationProperties(AssignmentProperties.class)
public class AssignmentConfig {}
