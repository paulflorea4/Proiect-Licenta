package com.gradingplatform.backend.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * Clears what a killed or crashed backend left in the sandbox (4.3e): runs once, when every bean exists
 * and before the application starts serving or any {@code ApplicationRunner} runs. Whatever starts
 * sandbox runs later (Phase 5's executor and its startup recovery, 5.2c) must start no run before
 * this: it removes every labelled container, so it must never see a run of this process.
 *
 * <p>It can never stop the application from starting.
 */
@Component
class SandboxStartupSweep implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(SandboxStartupSweep.class);

    private final DockerCliSandboxRunner runner;

    SandboxStartupSweep(DockerCliSandboxRunner runner) {
        this.runner = runner;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            runner.sweepOrphans();
        } catch (RuntimeException e) {
            log.warn("startup sweep of the sandbox failed: {}", e.toString());
        }
    }
}
