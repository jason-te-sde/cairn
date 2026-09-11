# Architecture

## The layering, and why the arrows only point one way

```
cairn-server    HTTP, metrics, cairnctl · owns one thread, publishes immutable states
   │
   ├── cairn-effects   at-least-once delivery + at-most-once application
   │
   ├── cairn-store     command log, snapshots, content-addressed blobs
   │        │
   │        └── cairn-codec   canonical encoding · zero dependencies
   │                 │
   └─────────────────┴── cairn-core   the rules, as one pure function · zero dependencies

cairn-testkit   simulator, invariants, a second implementation, the known flaws
                (depends on core, codec, store, effects — depended on by nothing)
```

`cairn-core` has an empty dependency list, and that is enforcement rather than aspiration: there is
nothing on its compile path to reach a disk or a clock with. `cairn-codec` likewise. Everything that
can fail, block, or vary between runs lives above them.

## The one function

```java
Transition apply(Registry state, long index, Command command)
```

Five commands, and the state is one immutable value. No threads, no clock, no I/O, no randomness.
The timestamp and the actor arrive as arguments, which is what makes a replay produce the state that
log produced the first time — on any machine, on any JDK. `design/0001-pure-kernel.md` has the
argument and the measured cost.

## One thread owns the kernel

```
       HTTP threads                 cairn-kernel (one)              cairn-dispatch (timer)
            │                              │                                │
  read ─────┼──► state (volatile) ◄────────┤                                │
            │                              │                                │
  write ────┼──► submit ───────────────────►│ append ─► fsync ─► apply       │
            │                              │      │                         │
            │                              │      └─► publish new state     │
            │                              │      └─► drain the outbox ◄────┘ every 2s
  upload ───┼──► blob store (hashing) ──► submit IngestBlob
```

Nothing in the kernel takes a lock, because nothing else touches it. Every command becomes a task on
one single-threaded executor, and each command publishes a new immutable `Registry` through a
`volatile` field.

**Reads do not go through that thread at all.** A reader takes one reference and holds a whole
consistent registry for as long as it likes. There is no such thing here as observing a half-applied
command, because there is no value that represents one. `EngineTest.readsAreLockFreeAndNeverSeeAHalfAppliedCommand`
drives writes and reads concurrently and asserts no reader ever sees two production versions.

**The dispatcher runs on the same thread**, after each command and on a timer. That is not a
performance choice — it is how the re-entrancy is avoided. A dispatcher on another thread would have
to propose its acknowledgement back through the queue, and a proposal made from inside a queued task
would deadlock on itself.

**Uploads happen off that thread.** An upload is I/O that can take minutes, so the bytes are
streamed to the blob store and hashed on the caller's thread, and only then is `IngestBlob`
proposed. The ordering is the contract: the registry never learns about an artifact that is not
already on disk and verified.

## The durability boundary

```
append ──► fsync ──► apply ──► answer the caller
```

Under `SYNC_EACH`, a command this sequence has answered for is a command that survives a power
loss. That ordering is stated in one place — `Engine.applyOnOwningThread` — rather than being a
thing to remember at each call site.

Recovery is the mirror image: load the newest snapshot that passes its checksum, then replay the
log above it. It is a fold over a pure function, twenty lines in `Recovery.open`, and it is the
*same* code that answers `cairnctl state --at=N`. There is no second implementation of "reconstruct
the state" to disagree with the first.

Two conditions abort rather than recover, and the distinction matters:

| | |
| --- | --- |
| A damaged record at the **tail** of the log | truncated. Nothing was acknowledged past it. |
| A gap between the snapshot and the log | **refuses to start.** Records that were acknowledged are gone, and serving a registry that skipped them silently is worse than an outage. |

`Recovery.checkpoint` is the only place the snapshot-then-discard ordering is written down. A crash
between the two leaves a durable snapshot and a log that still holds records below it, which is
harmless because applying an already-applied index is a no-op. A crash between them in the other
order is unrecoverable, which is why they are not two calls at each site that needs them.

## The path of a publish

```
client                server              log      kernel        outbox     dispatcher    world
  │  PUT /v1/artifacts   │                  │         │             │            │          │
  ├─────────────────────►│ stream + SHA-256 │         │             │            │          │
  │                      ├─ write, verify ──┤         │             │            │          │
  │                      ├─ IngestBlob ────►├─ fsync ─►             │            │          │
  │  ◄─── 201 digest ────┤                  │         │             │            │          │
  │  POST .../versions/v │                  │         │             │            │          │
  ├─────────────────────►├─ PublishVersion ─►├─ fsync ─►  apply      │            │          │
  │                      │                  │         ├─ effect #1 ─►            │          │
  │  ◄─── 201 version ───┤                  │         │             │            │          │
  │                      │                  │         │             ├─ deliver ──┼─────────►│
  │                      │                  │         │             │            ├─ Ack ───►│
  │                      │                  ├─ AckEffects ─► apply ─┤ (pruned)   │          │
```

The client's 201 arrives before delivery, on purpose: a publish is durable and answered as soon as
the log has it. Delivery is a separate activity that can fall behind without the write path
noticing, and the depth of that lag is the metric to watch.

## Where each guarantee lives

| Guarantee | Enforced by | Checked by |
| --- | --- | --- |
| A version's artifact never changes | `Kernel.publish` refuses a differing republish | I2, over the whole history of a run |
| At most one production version | promotion demotes the incumbent in one transition | I5, after every step |
| No dangling artifact | reference counting, and `Blob`'s constructor | I3, I4, recounted from scratch |
| No dangling provenance | `HAS_DESCENDANTS`, and tombstones are permanent | I9 |
| A rejection changes nothing | the kernel returns a state rather than mutating one | I10 |
| A duplicate log entry is a no-op | `Kernel.apply` is idempotent in the index | injected by the simulator |
| Exactly-once effects | dispatcher retries; consumer deduplicates on a watermark | I7, across injected crashes |
| Bytes match their digest | `BlobStore` hashes while writing | I11 |
| The outside world ends up agreeing | ordered delivery from a contiguous outbox | I12, once delivery catches up |
| The state is what the log says | canonical encoding | `cairnctl verify` |

## Reading the code

Half an hour, in this order:

| File | Why |
| --- | --- |
| `core/Kernel.java` | the one function; every rule is in it |
| `core/Effect.java` | why side effects are values here rather than statements |
| `core/Blob.java` | reference counting, and the field a simulation forced into existence |
| `codec/Codec.java` | why one state has exactly one byte string |
| `effects/Dispatcher.java` | the half of exactly-once the registry owns |
| `effects/IdempotentSink.java` | the other half, in four lines |
| `store/Recovery.java` | startup, and the ordering that makes a checkpoint safe |
| `server/Engine.java` | one thread, and why the dispatcher runs on it |
| `testkit/Invariants.java` | the twelve properties, as executable checks |
| `testkit/Simulator.java` | what is injected, and what is deliberately not modelled |
