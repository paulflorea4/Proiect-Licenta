package com.gradingplatform.backend.sandbox;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Language configs for tests that need a {@link SandboxProperties} without starting Spring. */
public final class TestLanguages {

    private TestLanguages() {}

    public static LanguageSpec java() {
        return new LanguageSpec(
                "sandbox-java:test", "Main.java", List.of("javac", "Main.java"), List.of("java", "-cp", ".", "Main"));
    }

    public static LanguageSpec python() {
        return new LanguageSpec("sandbox-python:test", "main.py", List.of(), List.of("python", "main.py"));
    }

    /** {@code JAVA} and {@code PYTHON}, as the application configures them. */
    public static SandboxProperties javaAndPython() {
        Map<String, LanguageSpec> languages = new LinkedHashMap<>();
        languages.put("JAVA", java());
        languages.put("PYTHON", python());
        return new SandboxProperties(languages);
    }
}
