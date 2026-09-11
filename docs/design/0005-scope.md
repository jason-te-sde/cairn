# 5. Scope: what is deliberately missing

A reader who cannot tell an omission from an oversight has to assume the worst. This is the list,
with reasoning.

## What this project took from the system it is derived from, and what it left

cairn is derived from a Raft-backed AI model registry: a fork of `raft-java` with a model-metadata
state machine, RocksDB storage, MinIO for artifacts, Kafka for lifecycle events, and Redis for
caching and locking. It takes the **problem** and leaves most of the **parts**. Naming both halves
matters, because "modelled on" can otherwise mean anything.

**Taken:** the domain (a replicated registry of model versions and their artifacts), and the
specific defects in how that system handled it — a delimiter-joined record format, effects published
from inside the apply path, an apply error swallowed and logged, a restore that aliased its live
storage to the snapshot, and a client-supplied digest that nothing verified. Five of the eight
reintroducible flaws in `cairn-testkit` are those defects, put back on purpose so the suite can be
shown to catch them.

**Left, deliberately:**

| Not taken | Why |
| --- | --- |
| **Raft** | See below. cairn is the state machine, not the consensus. |
| **RocksDB** | The state is one immutable value with a canonical encoding; a snapshot is a file. An embedded LSM tree would add a native dependency, and its checkpoint/restore API is where the original's worst defect lived. |
| **MinIO / an S3 client** | `BlobStore` is a port with a filesystem implementation. An S3 driver is configuration and credentials, not design, and adding one would put a cloud SDK on the compile path of a project whose dependency list is one entry long. |
| **Kafka** | `EffectSink` is a port. The default implementation collects artifacts and appends effects to a JSON-lines file with their sequence numbers, which is a queue any consumer can tail and deduplicate against. Shipping a broker client would tie the registry's availability to the broker's. |
| **Redis for caching** | Reads are lock-free off an immutable state reference; there is nothing to cache that is cheaper than reading it. A serving-tier cache belongs in the serving tier, and `ProductionChanged` is the effect that invalidates it. |
| **Redis for distributed locking** | A lock is what a system reaches for when it has no agreed order. This one has a log. |
| **A model-serving or inference path** | A different project. The registry answers "what should be loaded"; loading it is somebody else's job. |
| **Spring** | See `0006-no-framework.md`. |

## Consensus

**Deliberately absent, and it is the load-bearing scope decision.** cairn is a state machine: given
an agreed order of commands, it decides what the registry is and what the outside world is owed. It
does not decide what the order is.

That is not a gap left for later — it is what makes the rest of the project coherent. The kernel is
a pure function precisely so that something else can own the ordering, and the seam is
`CommandLog`: append a command, get an index. A single-writer file implementation ships; a
replicated one is a Raft group whose apply callback calls `Kernel.apply`.

The author's previous project, [keel](https://github.com/jason-te-sde/keel), is a from-scratch Raft
implementation with a state machine port of exactly this shape. The two compose. Doing consensus
again here would have meant a worse Raft and a shallower registry.

The simulator reflects the boundary honestly: it models **one agreed log and several replicas
applying it**, injecting every fault that can occur between a log and a replica's state. It cannot
model two replicas being fed *different* logs, because nothing in this project decides what the log
is.

## A persistent sorted map

**Absent, and it is the one measured performance limit.** Publishing into a model that already has
*N* versions copies *N* entries, so publishing *N* versions into one model is O(N²). At 10,000
versions in one model, publishing runs at 9,200 a second; spread over 200 models it is 43,000.
`KernelScalingTest` prints the curve.

The fix is a persistent sorted map with structural sharing, making each publish O(log N). Not taken
because writing a correct HAMT or finger tree with no dependencies is a data-structure project, and
a registry's shape is hundreds of versions per model rather than tens of thousands. This is the
first thing to revisit if that stops being true.

## Backpressure on the outbox

**Absent.** A consumer that is down forever means an outbox that grows forever. Refusing writes
because a consumer is behind is a policy decision a library should not make for an operator, so the
depth is exposed as a metric and `docs/operations.md` says to alert on it. That is the honest
version of not solving it rather than a claim that it does not matter.

## Semantic version ordering

**Never planned.** `VersionId` sorts lexicographically, so `1.10.0` sorts before `1.9.0`. That is
wrong as a version order and right as a map order, and the registry never needs the former: "what
should serving load" is `Stage.PRODUCTION`, a fact somebody asserted, rather than a conclusion drawn
from a string. A registry that guessed which version was newest would eventually guess wrong about
`1.0.0-rc2` versus `1.0.0`, and it would do it silently.

## Multi-tenancy, projects, namespaces

**Out of scope.** One flat model namespace. Prefixing a model name is available and costs nothing
(`team-a.fraud` is a legal identifier); real tenancy means per-tenant authorization and quotas,
which is a different project.

## Authorization beyond two tokens

**Thin on purpose.** A client token and a separate admin token, because ejecting a version is not
the same privilege as publishing one. No users, no roles, no per-model permissions. `SECURITY.md`
states what that does and does not defend against; in front of anything real, this sits behind a
gateway that has an identity model.

## TLS

**Absent.** The server speaks plain HTTP and refuses to bind a non-loopback address without a token.
Terminating TLS is a reverse proxy's job in every deployment that has one, and building it in would
mean certificate handling in every test for a benefit most operators already have.

## Garbage collection of tombstones and old log segments

**Partial.** Log segments below a snapshot are released; snapshots are pruned to the most recent
two. Tombstones are never removed, which is deliberate (see `0004-immutable-versions.md`) and means
metadata grows monotonically. At a few hundred bytes per version record, `docs/operations.md`
concludes this is not the growth to worry about.

## Torn-sector and reordered-write injection

**Absent from the simulator.** The simulated disk models the *sync boundary* — unsynced writes
vanish on a crash, which is the property the design depends on — but not a disk that writes sectors
out of order or half-writes one. The real log's recovery is tested separately by truncating and
corrupting actual files, and `docs/testing.md` lists this among the gaps.

## A single-module build

**Rejected.** Six modules is more build surface than one, and the dependency direction is the point:
`cairn-core` cannot accidentally import a socket, because there is nothing on its compile path to
import. The enforcer runs `reactorModuleConvergence` and CI has a clean-clone job, because a
multi-module build's characteristic failure is a module that is declared and never committed.
