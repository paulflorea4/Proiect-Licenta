# Sandbox runner image for Python (4.2b). The base image was approved at the 4.2a gate.
#
# Python has no build step: the same image runs `python main.py` in each test container
# (sandbox.languages.PYTHON in backend/src/main/resources/application.yml). The container is always
# started by the backend's SandboxRunner with the restrictions from the root CLAUDE.md (no network,
# read-only root filesystem, dropped capabilities, resource limits); nothing here replaces them.
#
# Build (from the repository root):
#   docker build -t grading-sandbox-python:dev infra/sandbox/python
FROM python:3.12-slim

# No .pyc files (the filesystem is read-only anyway, and the working directory is not ours to
# litter) and unbuffered output, so what a program printed before a timeout is not lost in a buffer.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Fixed numeric ids, so the backend can start the container with `--user 10001:10001` and prepare
# the host working directory for exactly that user. No home directory and no login shell.
RUN groupadd --gid 10001 sandbox \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin sandbox \
    && mkdir /work \
    && chown 10001:10001 /work \
    # A submission has nothing to install, so the package installer is not shipped.
    && python -m pip uninstall -y pip

# The submission's directory is mounted here; the source file is written to it and commands run in it.
WORKDIR /work

USER 10001:10001
