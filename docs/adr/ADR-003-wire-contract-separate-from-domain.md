# ADR-003 — A wire contract separate from the domain model

- **Status:** Accepted
- **Date:** 2026-08-11
- **Milestone:** M3 — Kafka ingestion

## Context

`TelemetryEvent` is a record of plain fields. Jackson can serialise it with no annotations and no
configuration, so publishing it directly to Kafka is one line of code and zero new classes.

That convenience hides a coupling. A message on a topic outlives the process that wrote it: it
sits in the log for the retention period, it may be in a dead-letter topic awaiting replay, and it
may be read by a consumer built from a different revision of the code. If the domain type *is* the
wire format, then every internal change becomes a protocol change — renaming a field, extracting a
value object, tightening a type. None of those would fail to compile, and all of them would break
consumers.

The two also change for unrelated reasons. The domain changes when the business model is
understood better; the wire format changes when producers and consumers need to agree on
something new. Tying them together means neither can move without the other's permission.

## Decision

Introduce `TelemetryMessage`, an explicit contract in
`telemetry/infrastructure/messaging`, with a hand-written `TelemetryMessageMapper` translating both
ways.

```json
{
  "schemaVersion": 1,
  "eventId": "…",
  "machineId": "…",
  "occurredAt": "2026-01-15T10:00:00Z",
  "readings": {
    "temperatureCelsius": 62.5,
    "vibrationMillimetresPerSecond": 2.4,
    "pressureBar": 5.9,
    "powerConsumptionKilowatts": 35.1,
    "rotationSpeedRpm": 1450.0
  }
}
```

Three properties are deliberate:

- **`schemaVersion` from the first message.** A consumer meeting version 2 can reject or adapt it
  on purpose, rather than inferring intent from the shape of the payload. Adding a version field
  after the fact requires supporting the unversioned case forever.
- **Units in field names.** `pressureBar`, not `pressure`. A consumer in another language cannot
  silently assume psi.
- **No type headers on the wire.** `spring.json.add.type.headers=false`, so the payload does not
  carry a Java class name that would make it unusable outside the JVM.

JSON, not Avro or Protobuf. Being able to read a record straight off a topic during development is
worth more today than the bytes a binary format saves. A schema registry with Avro would earn its
place once contracts cross team boundaries, payload size matters, or compatibility has to be
enforced mechanically rather than by review — none of which is true for a single application.

## Alternatives considered

**Serialise the domain record.** Free, and makes every refactor a wire break. Rejected.

**Domain record plus Jackson annotations.** Keeps one class, at the price of serialisation concerns
inside the domain and no independent versioning. It also breaks the property that the domain has no
framework dependency, which every layer of this project rests on.

**MapStruct for the mapping.** Would pay off across dozens of types with deep nesting. Here it
replaces fifteen readable lines with a dependency and an annotation processor — and those fifteen
lines are precisely where a wire change should be noticed by a reviewer.

## Consequences

**Positive**

- The domain can be refactored without touching the protocol, and vice versa.
- Jackson stays at the edge. `DomainPurityTest` fails the build if any framework or serialisation
  import appears in a domain package, so the boundary is enforced rather than merely intended.
- Validation happens at the boundary by construction: the mapper builds domain objects, so their
  invariants reject a corrupt payload before it enters the system. An unknown `schemaVersion`
  raises a dedicated, non-retryable exception that routes straight to the dead-letter topic.

**Negative**

- Two types describing the same information, and a mapper to keep in step. The round-trip test
  exists to catch drift.
- The mapping is a real cost per message, paid on every event. Measured against the alternative of
  a broken protocol, it is worth paying; whether it shows up at all is a question for M15.
- A version field is not a schema registry. Nothing prevents a producer from lying about its
  version, and compatibility is enforced by review rather than by a tool.

## Related

- ADR-002 covers partitioning, the other half of the messaging contract.
- Enforced by `DomainPurityTest`, `TelemetryMessageMapperTest` and
  `TelemetryMessageSerializationTest`, which pins the exact JSON.
