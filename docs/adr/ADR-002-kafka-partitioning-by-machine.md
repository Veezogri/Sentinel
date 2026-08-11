# ADR-002 — Partition telemetry by machineId

- **Status:** Accepted
- **Date:** 2026-08-11
- **Milestone:** M3 — Kafka ingestion

## Context

Telemetry flows through a topic that several consumers read in parallel. Kafka's ordering
guarantee is narrower than it is often assumed to be: **records are ordered within a partition,
and not at all across partitions**. A topic with six partitions is six independent ordered logs,
consumed concurrently.

The platform depends on order in one specific place. `MachineState` holds the last known readings
of a machine, and "last" only means something if the events of that machine are processed in the
order they were produced. If two consecutive samples of the same pump land on different
partitions, they can be handled by two threads in either order, and the stored state can end up
holding the older of the two — intermittently, under load, with no error anywhere.

Order is only needed *per machine*. There is no meaning to the relative order of a pump's reading
and a turbine's.

## Decision

Publish every telemetry record with **`key = machineId`**.

Kafka's default partitioner hashes the key, so a given machine's events always resolve to the same
partition, and are therefore appended to one log in production order and consumed in that order by
whichever group member owns the partition.

`sentinel.telemetry.raw` is created with **6 partitions** and, locally, replication factor 1.

Six is a starting point, not a derived number. It is explicitly not tied to the machine count, the
number of machine types or the CPU count. It is enough to run several consumers concurrently and
to observe a rebalance, while staying cheap on a single broker. It also sets the ceiling on
consumer parallelism: a partition is read by at most one member of a group at a time, so the
seventh consumer thread would idle.

## Alternatives considered

**No key (round-robin).** Best possible spread across partitions, and destroys per-machine
ordering completely. Rejected: the spread is not a problem worth having, and the ordering is.

**Key by machine type.** Five keys for five types, so at most five partitions ever carry data and
one slow machine type stalls every machine of that type. Worse balance *and* coarser ordering than
keying by machine.

**One partition for the whole topic.** Gives total ordering and caps throughput at a single
consumer. Correct but not scalable, and it would hide exactly the concurrency questions this
project exists to answer.

**Explicit partition number per record.** Removes the hash indirection at the cost of pinning
partition assignment into the producer, so a change in partition count becomes a code change.
Rejected as the same guarantee with more coupling.

## Consequences

**Positive**

- Events of a machine are ordered relative to each other, end to end.
- Load spreads across partitions as long as machine identifiers are well distributed, which random
  UUIDs are.
- Consumer parallelism can grow to six without touching the producer.

**Negative**

- **Key skew is possible.** One extremely chatty machine puts all its load on one partition, and no
  amount of extra consumers helps. Acceptable here because machines report at a uniform rate; it
  would not be in a system with hot keys.
- **Partition count is effectively immutable.** Adding partitions rehashes keys, so a machine can
  move to a different partition — and its in-flight events can then be processed out of order
  across the change. Repartitioning is a migration, not a config tweak.
- **Ordering is a guarantee about delivery, not about processing.** A retry, a replay from an
  earlier offset, or a rebalance mid-batch can still present an older event after a newer one. The
  guarantee reduces the frequency of out-of-order events; it does not remove the need to handle
  them. `MachineState.apply` therefore stays defensive and declines events that are not strictly
  newer (ADR-001, M1).

## Related

- Late events and duplicates are different problems: see ADR-003 on the wire contract and the
  "Ordering and duplicates" section of the README.
- Verified by `TelemetryPartitioningIT`, which asserts against a real broker that all records of
  one machine share a partition.
