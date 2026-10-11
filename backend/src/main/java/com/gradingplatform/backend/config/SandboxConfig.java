package com.gradingplatform.backend.config;

import com.gradingplatform.backend.sandbox.SandboxProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SandboxProperties}, the languages config. */
@Configuration
@EnableConfigurationProperties(SandboxProperties.class)
public class SandboxConfig {}
