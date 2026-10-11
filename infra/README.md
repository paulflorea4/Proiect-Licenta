# infra

Local development infrastructure for the Grading Platform. Nothing here is application code.

Planned contents:

- **Database** — a `docker-compose.yml` that runs PostgreSQL 16 for local development (added in 0.2a).
- **Sandbox images** — the Docker images used to compile and run submitted code, one per supported language, in `sandbox/<language>/Dockerfile` (added in 4.2b). Build from the repository root:

  ```
  docker build -t grading-sandbox-java:dev infra/sandbox/java
  docker build -t grading-sandbox-python:dev infra/sandbox/python
  ```

  The tags are the `image` values of `sandbox.languages` in `backend/src/main/resources/application.yml`. The images only provide a non-root user (`10001:10001`) and the toolchain; the sandbox restrictions (no network, read-only filesystem, limits) are applied by the backend when it starts a container. The backend's `SandboxImagesTests` builds them itself.
