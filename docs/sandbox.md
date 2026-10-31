# Sandbox readiness (experimental)

`classrevive sandbox-check` performs a bounded read-only `docker info` against a fixed local endpoint. It does not start Docker, pull images, create containers, load target classes or execute a target. Exit 1 means unavailable/unsupported; exit 0 means only that a Linux daemon answered. `AVAILABLE_NOT_VERIFIED` is deliberately not an execution safety guarantee.

Windows probes Docker Desktop's Linux named pipe; Linux/macOS currently probe `/var/run/docker.sock`. Rootless/custom endpoints are not supported yet. Remote Docker contexts and environment endpoint overrides are not used.

The experimental Java policy builder specifies a non-root container, read-only root/input, no network, no capabilities, no-new-privileges, fixed local image ID, no image pull, CPU/memory/process limits and bounded tmpfs. It is not wired to execution. It assumes a prepared isolated input folder and a reviewed Temurin-compatible image with `/opt/java/openjdk/bin/java`.

Before enabling behavior comparison, implementation still needs image inspection (including inherited environment/volumes), prepared input validation, real containment conformance tests, bounded output capture, timeout/container cleanup, and a test/result comparison protocol. Container policy is not protection against all kernel/runtime vulnerabilities; hostile samples require a disposable isolated environment.

Local evidence on 2026-10-15: Docker CLI installed; Linux daemon pipe absent. No target was executed, and no host-execution fallback was attempted. Behavioral equivalence remains NOT_VERIFIED.

The [behavior protocol](behavior-protocol.md) defines immutable paired observations and conservative comparison statuses. It is implemented separately from execution and cannot itself establish that containment or actual tests occurred.

Reference: https://docs.docker.com/reference/cli/docker/container/run/
