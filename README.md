# Sentinel

**Real-Time Industrial Monitoring Platform** — Java 21 · Spring Boot · Kafka · Angular

> **Project status: Milestone 0 — Bootstrap.**
> This README documents only what exists in the repository today. Everything else lives in
> the [Roadmap](#roadmap) until it is actually implemented. No performance figure will appear
> here unless it comes from a real measured run.

---

## Overview

Sentinel supervises a fleet of industrial machines. Machines continuously emit telemetry
(temperature, vibration, pressure, power consumption, rotation speed). The platform ingests
that stream, maintains each machine's current state, persists history, detects abnormal
behaviour through a rule engine, manages the alert lifecycle, and pushes changes in real time
to an operator dashboard.

The design goal is an event-driven system that stays correct under duplication, retries and
partial outages — not a CRUD application with a message broker bolted on.

---

## Target architecture

```text
                   ┌─────────────────────┐
                   │  Machine Simulator  │
                   └──────────┬──────────┘
                              │ Telemetry
                              ▼
                   ┌─────────────────────┐
                   │        Kafka        │   keyed by machineId
                   └──────────┬──────────┘
                              ▼
                 ┌─────────────────────────┐
                 │     Sentinel Backend    │
                 │   Java 21 / Spring Boot │
                 └────────────┬────────────┘
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
┌────────────────┐   ┌────────────────┐   ┌────────────────┐
│     Redis      │   │   PostgreSQL   │   │  Alert Engine  │
│ current state  │   │    history     │   │     rules      │
└────────────────┘   └────────────────┘   └───────┬────────┘
                                                  ▼
                                          REST API + WebSocket
                                                  ▼
                                        ┌───────────────────┐
                                        │ Angular Dashboard │
                                        └───────────────────┘
```

**Implemented today:** the Spring Boot application shell and its health surface, plus the
PostgreSQL / Kafka / Redis containers. Nothing in the data path exists yet.

The backend is a **modular monolith**: one deployable, with domains separated by package
(`machine`, `telemetry`, `alert`, `rule`, `realtime`, `security`, …). This keeps transactional
consistency simple while the boundaries stay explicit enough to split later if a genuine
scaling or ownership reason appears.

---

## Technology stack

| Layer | Technology | Status |
|---|---|---|
| Language | Java 21 (Temurin) | in use |
| Framework | Spring Boot 3.5.16 (Web, Actuator) | in use |
| Build | Maven 3.9.16 via wrapper | in use |
| Database | PostgreSQL 16 | container provisioned |
| Streaming | Apache Kafka 3.9 (KRaft) | container provisioned |
| Cache / state | Redis 7 | container provisioned |
| Tests | JUnit 5, Spring Boot Test, MockMvc | in use |
| CI | GitHub Actions | in use |
| Frontend | Angular | not started (M8) |

Dependencies are added by the milestone that needs them. There is deliberately no JPA, Kafka
or Redis client on the backend classpath yet — which is why the test suite runs green with no
container running.

---

## Running locally

### Prerequisites

- **JDK 21** — the build enforces it and fails fast on anything older
- **Docker** with Compose v2 (only needed for the infrastructure, not for the build)

If JDK 21 is not your default JVM, point the build at it explicitly:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS
java -version                                       # expect 21.x
```

### Infrastructure

```bash
cp .env.example .env
docker compose up -d --wait
docker compose ps          # postgres, redis and kafka should report healthy
```

### Backend

```bash
cd backend
./mvnw clean verify        # compile + tests
./mvnw spring-boot:run     # starts on http://localhost:8080
```

### Verify it is up

```bash
curl -s localhost:8080/api/v1/system/health
# {"status":"UP","application":"sentinel","version":"0.1.0-SNAPSHOT","timestamp":"..."}

curl -s localhost:8080/actuator/health
# {"status":"UP"}
```

### Shut down

```bash
docker compose down          # keeps volumes
docker compose down -v       # also drops the data
```

---

## Configuration

Configuration comes from environment variables, with development defaults baked in so the
project starts with no setup. Copy `.env.example` to `.env` to override. **`.env` is
gitignored — no real credential is ever committed.**

| Variable | Default | Used by |
|---|---|---|
| `SERVER_PORT` | `8080` | backend |
| `DB_HOST` / `DB_PORT` | `localhost` / `5432` | PostgreSQL container |
| `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `sentinel` | PostgreSQL container |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka container |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis container |

The database, Kafka and Redis variables currently configure the containers only; the backend
starts consuming them when the corresponding milestone lands.

### Kafka listeners

Kafka advertises two listeners because one hostname cannot be valid both inside and outside
the Docker network:

| From | Address |
|---|---|
| Host (IDE, local backend, tests) | `localhost:9092` |
| Another container on the `sentinel` network | `kafka:29092` |

Topic auto-creation is **disabled**: partition count and retention are architectural
decisions, so topics get declared explicitly rather than appearing by accident.

---

## REST API

Base path `/api/v1`. Only one endpoint exists so far.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/v1/system/health` | Liveness + build identity of the backend |

`/api/v1/system/health` deliberately coexists with Actuator. Actuator answers *operational
readiness* (dependency health) and becomes internal and authenticated later; the `/api/v1`
endpoint answers *liveness* on the public versioned API — "the backend is reachable, and this
is the build you are talking to" — so it performs no dependency probing and a degraded
database must not make it fail.

---

## Testing

```bash
cd backend && ./mvnw test
```

| Test | What it protects |
|---|---|
| `SentinelApplicationTests` | full context startup — catches a broken bean graph or an unresolvable placeholder |
| `SystemHealthControllerTest` | health contract: HTTP status, JSON shape, and that the build version is really substituted at package time |

No test requires a running container at this stage. Testcontainers-based integration tests
arrive with persistence (M4).

---

## Engineering decisions

Recorded as they are made, with the reasoning that justified them. Architecture Decision
Records land in `docs/adr/` from Milestone 3 onward, once the first non-obvious trade-offs
(Kafka partitioning, idempotency strategy) are actually taken.

Decisions taken so far:

- **Modular monolith, not microservices.** One deployable with strict package boundaries.
  Splitting a system into services before knowing its real coupling and load profile buys
  distributed-systems problems with no matching benefit.
- **Kafka in KRaft mode.** Removes ZooKeeper from the local stack — one container instead of
  two, and it matches how Kafka is deployed today.
- **No unused dependency on the classpath.** Each starter enters with the milestone that
  exercises it. A dependency that is present but unused is dead weight that still has to be
  patched, and it makes the bootstrap test lie about what the application needs to start.
- **Enforced JDK floor.** `maven-enforcer-plugin` fails the build below JDK 21, which is
  cheaper to diagnose than an unsupported class-file version error deep in the build.

---

## Known limitations

At Milestone 0, this is a bootstrap and nothing more:

- No domain model, no telemetry ingestion, no alerting, no persistence, no frontend.
- The backend does not connect to PostgreSQL, Kafka or Redis yet — the containers are
  provisioned and healthy, but unused.
- `/actuator/health` reports only application liveness, since there is no dependency to probe.
- No authentication: every endpoint is currently public (security lands in M12).
- The Docker Compose stack is a single-node development setup — one Kafka broker, no
  replication, no TLS. It is not a production topology.
- CI runs unit tests only; there is no integration-test, lint or Docker build stage yet.

---

## Roadmap

- [x] **M0** — Bootstrap: backend skeleton, infrastructure, CI, health endpoint
- [ ] **M1** — Domain model: Machine, TelemetryEvent, MachineState, Alert
- [ ] **M2** — Telemetry simulator with stateful, realistic value evolution
- [ ] **M3** — Kafka ingestion: serialization, partitioning by `machineId`, consumer groups
- [ ] **M4** — PostgreSQL: Flyway, telemetry history, indexes, Testcontainers
- [ ] **M5** — Redis: current state, last-seen tracking, idempotency
- [ ] **M6** — Alert engine: rules, deduplication, cooldown, lifecycle
- [ ] **M7** — REST API: pagination, filtering, error handling
- [ ] **M8** — Angular bootstrap: shell, routing, Material
- [ ] **M9** — Angular machines: list, filters, detail, telemetry charts
- [ ] **M10** — WebSocket: server broadcasting, client reconnection
- [ ] **M11** — Angular alerts: list, filtering, acknowledgment, live updates
- [ ] **M12** — Security: JWT, `OPERATOR` / `ADMIN` roles, guards, interceptor
- [ ] **M13** — Resilience: retry, backoff, dead-letter queue, failure scenarios
- [ ] **M14** — Observability: Micrometer, Prometheus, Grafana, structured logging
- [ ] **M15** — Load testing: 100 / 1 000 / 10 000 machines, measured
- [ ] **M16** — Advanced: batching, virtual threads, tuning — driven by M15 results
- [ ] **M17** — Portfolio polish: diagrams, ADRs, screenshots, benchmark results

---

## Repository layout

```text
sentinel/
├── backend/                  Spring Boot application (Java 21, Maven)
│   ├── src/main/java/com/sentinel/
│   ├── src/main/resources/
│   └── src/test/java/com/sentinel/
├── .github/workflows/        CI pipelines
├── docker-compose.yml        Local infrastructure
└── .env.example              Configuration template
```

`frontend/`, `simulator/`, `infrastructure/` and `docs/` appear as the milestones that fill
them land — empty scaffolding directories are not committed ahead of time.
