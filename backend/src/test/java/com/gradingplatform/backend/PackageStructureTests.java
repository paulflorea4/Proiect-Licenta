package com.gradingplatform.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 2.1b: the agreed package layout exists (by layer, plus room for `sandbox` and `ai`). Java cannot
 * keep an empty package in git, so each one is anchored by a `package-info.java` that says what
 * belongs there; this test keeps them from being deleted or renamed by accident.
 */
class PackageStructureTests {

    private static final Path ROOT = Path.of("src/main/java/com/gradingplatform/backend");

    private static final List<String> PACKAGES =
            List.of("controller", "service", "repository", "entity", "dto", "config", "security", "sandbox", "ai");

    @Test
    void everyAgreedPackageIsDocumentedByAPackageInfo() {
        for (String name : PACKAGES) {
            Path info = ROOT.resolve(name).resolve("package-info.java");

            assertThat(info).as(name).isRegularFile();
            assertThat(read(info))
                    .as(name)
                    .contains("package com.gradingplatform.backend." + name + ";")
                    .contains("/**");
        }
    }

    @Test
    void noUnexpectedTopLevelPackageHasAppeared() throws Exception {
        try (var children = Files.list(ROOT)) {
            List<String> directories = children.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();

            assertThat(PACKAGES).containsAll(directories);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
