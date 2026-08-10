# ADR-001 — Machine state modelling

- **Status:** Accepted
- **Date:** 2026-08-10
- **Milestone:** M1 — Domain model

## Context

The initial specification described a machine with a single status field:

```
MachineStatus = { ONLINE, OFFLINE, WARNING, CRITICAL, MAINTENANCE }
```

and placed it, together with `lastSeenAt`, on the `Machine` object itself.

Two problems appeared as soon as concrete scenarios were written down.

**The enum conflates independent facts.** `ONLINE`/`OFFLINE` answers "is telemetry arriving?".
`WARNING`/`CRITICAL` answers "is the equipment behaving correctly?". `MAINTENANCE` answers
"has an operator declared this machine out of supervision?". These vary independently, so a
single slot cannot hold them. Concretely:

- A machine at 98 °C that stops reporting becomes `OFFLINE`, silently discarding the fact that
  it was `CRITICAL` — precisely the information an operator needs to decide whether the silence
  is benign.
- A machine under maintenance that overheats can be `MAINTENANCE` or `CRITICAL`, not both.
- The set is not extensible: adding a fourth concern would multiply the values combinatorially.

**Update frequency differs by orders of magnitude.** A machine reports about once per second.
Its name and type change perhaps once a year. Holding both on one object means every telemetry
event rewrites the registry record.

## Decision

**Split the status into three orthogonal enums**, each answering one question:

| Enum | Question | Values |
|---|---|---|
| `ConnectivityStatus` | Is telemetry arriving? | `ONLINE`, `OFFLINE` |
| `HealthStatus` | Is the equipment behaving correctly? | `NORMAL`, `WARNING`, `CRITICAL` |
| `OperationalMode` | Is it under supervision? | `IN_SERVICE`, `MAINTENANCE` |

**Split the machine into two objects** along the update-frequency boundary:

- `Machine` — identity and configuration: `id`, `name`, `type`, `operationalMode`, `registeredAt`.
- `MachineState` — runtime state: `machineId`, `latestReadings`, `lastTelemetryAt`,
  `healthStatus`, `lastUpdatedAt`.

**Derive connectivity; store health.** `ConnectivityStatus` is not a field. It is computed by
`MachineState.connectivityAt(now, offlineAfter)`, because it is a pure function of elapsed time:
a stored copy is correct when written and wrong a minute later, and keeping it accurate would
require a periodic sweep over every machine. `HealthStatus` *is* stored, because it is the
recorded outcome of a rule evaluation and cannot be recovered from a clock.

## Alternatives considered

**Keep the single enum.** Simplest, and matches the original brief. Rejected because it
destroys information at exactly the moments that matter — a machine going quiet while critical
is the scenario a monitoring platform exists for.

**Split the statuses but keep one `Machine` object.** Fixes the modelling problem, not the write
amplification: the registry row would still be rewritten on every event, turning a low-volume
relational table into a hot write path and coupling the fleet registry to telemetry throughput.

**Store connectivity as a field, refreshed by a scheduled job.** The common approach, and the
reason many systems end up scanning their whole machine table every second. Rejected: the same
answer is available for free from a subtraction at read time.

## Consequences

**Positive**

- Health survives a machine going offline.
- Offline detection needs no scheduled table scan for the *query* path — the API and dashboard
  compute connectivity per machine on read.
- The two objects map cleanly onto their eventual stores: `Machine` to PostgreSQL (M4),
  `MachineState` to Redis (M5), each matching its real access pattern.
- Maintenance mode is available as an alert-suppression input (M6) without polluting health.

**Negative**

- Three enums instead of one, and callers that want a single badge for the UI must combine them.
  Accepted: that combination is a presentation concern, and it is better to compose a display
  value from precise facts than to reconstruct precise facts from a lossy one.
- Rendering a machine requires reading two objects rather than one.
- Deriving connectivity means an *event* ("machine X just went offline") is still not free.
  Pushing that transition over WebSocket will need something time-driven; deriving on read
  removes the cost from the query path, not from change detection. That mechanism is deliberately
  left to M5 rather than guessed at now.

## Related

- Alert lifecycle transitions: see `Alert` and `AlertStatus`.
- Ordering guarantees relied on by `MachineState.apply`: to be documented with Kafka
  partitioning (M3).
