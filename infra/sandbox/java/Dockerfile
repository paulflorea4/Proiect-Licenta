# Sandbox runner image for Java (4.2b). The base image was approved at the 4.2a gate.
#
# One image does both steps: `javac` in the compile container, `java` in each test container
# (sandbox.languages.JAVA in backend/src/main/resources/application.yml). The container is always
# started by the backend's SandboxRunner with the restrictions from the root CLAUDE.md (no network,
# read-only root filesystem, dropped capabilities, resource limits); nothing here replaces them.
#
# Build (from the repository root):
#   docker build -t grading-sandbox-java:dev infra/sandbox/java
FROM eclipse-temurin:17-jdk-jammy

# Fixed numeric ids, so the backend can start the container with `--user 10001:10001` and prepare
# the host working directory for exactly that user. No home directory and no login shell.
RUN groupadd --gid 10001 sandbox \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin sandbox \
    && mkdir /work \
    && chown 10001:10001 /work \
    # Nothing a submission needs to compile or run, and the first tools anyone reaches for.
    && apt-get purge -y --auto-remove curl wget \
    && rm -rf /var/lib/apt/lists/*

# The submission's directory is mounted here; the source file is written to it and commands run in it.
WORKDIR /work

# No entrypoint (8.2g, the human's decision on the 7.7b suggestion). The base image's
# /__cacert_entrypoint.sh ran every command through a shell, so a compile or run command missing
# from the image came back as the shell's exit 127, which looks exactly like a program's own
# exit(127) and was graded as the student's COMPILE_ERROR / RUNTIME_ERROR. Without it Docker cannot
# start such a container at all, and the runner reports SANDBOX_ERROR, as on the Python image. The
# script only imports host CA certificates (USE_SYSTEM_CA_CERTS), which a sandbox without network
# never needs. SandboxImagesTests pins that no runner image has an entrypoint.
ENTRYPOINT []

USER 10001:10001
