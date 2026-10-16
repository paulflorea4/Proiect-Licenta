package com.gradingplatform.backend.config;

import com.gradingplatform.backend.service.SubmissionWorkerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link SubmissionWorkerProperties}, the size of the submission worker pool (5.2a). */
@Configuration
@EnableConfigurationProperties(SubmissionWorkerProperties.class)
public class SubmissionWorkerConfig {}
