# 3. Side effects are values, not statements

The kernel performs no side effect. When a command implies something has to happen outside the
registry — an event published, a cache invalidated, an artifact's bytes deleted — the kernel appends
an `Effect` to replicated state with a sequence number and returns. Somebody else delivers it
afterwards.

This is the decision the whole project is organized around.

## What it replaces

The state machine this is derived from published to Kafka from inside its `applyData` method:

```java
// inside the state machine's apply path
storage.put(CF_MODELS, key, value);
if (eventProducer != null && eventProducer.isEnabled()) {
    eventProducer.sendEvent(new ModelEvent(modelId, version, CREATED, s3Key, description));
}
```

That is wrong in two directions at once.

- **A replica replaying its log republishes everything it has ever published.** Replay is the normal
  path — it happens on every restart and after every snapshot restore — so this is not an edge case,
  it is Tuesday. Downstream, every consumer sees the entire history again.
- **A publish that fails is lost while the state change that caused it is durable.** The `if` above
  has no `else`, and even with one there is nowhere to put the retry: the state has already been
  written and the log has moved on.

Either way the log and the outside world disagree, and nothing in the system can tell you that they
do. That is the part that makes it serious: there is no metric, no check, and no query that reveals
it.

## How exactly-once is actually achieved

Not by a distributed transaction. By splitting the guarantee in half and being explicit about which
half lives where:

**At least once, from the dispatcher.** `Dispatcher` reads the outbox in order, delivers, and then
moves the watermark by proposing `AckEffects` *through the log*. Every crash in that sequence
results in redelivery rather than loss:

| Crash point | What happens |
| --- | --- |
| before delivery | the effect is still in the outbox; delivered next time |
| after delivery, before the acknowledgement commits | redelivered; the consumer discards it |
| after the acknowledgement is appended, before it is applied | log replay applies it |
| the consumer refuses | nothing is acknowledged; retried, and the outbox depth grows |

**At most once, from the consumer.** `IdempotentSink` discards an offer at or below what an
`AppliedLedger` already records, and advances the ledger only *after* the delivery has returned.
Because delivery is in order, one number is complete state for the whole stream — a deduplication
scheme over unordered deliveries needs a set that grows forever, or a window that silently stops
protecting you.

The ordering inside the consumer is the whole content of that class: deliver, then record.
Recording first makes a crash between the two lose the effect permanently and silently, which is the
wrong direction to fail in. `Flaw.WATERMARK_BEFORE_DELIVERY` is that mistake, put back on purpose,
and invariant I7 catches it.

## Ordering is a guarantee, not an accident

The outbox is contiguous and sorted, delivery follows it, and a refusal stops the run rather than
skipping ahead. That is what makes this sequence true rather than hopeful: promoting a successor
produces, in one atomic transition,

```
#6 stage_changed  fraud@1.0.0  production -> archived
#7 stage_changed  fraud@2.0.0  staging    -> production
#8 production_changed  fraud  -> 2.0.0
```

A consumer replaying that stream never holds two production versions, not even for one message.

## `AckEffects` is privileged, and that is a finding

A simulation run failed invariant I7 with

```
effect #25 is acknowledged through 26 but was never applied downstream
```

because the command generator was proposing `AckEffects` like any other command, and an
acknowledgement from something that had not delivered anything moved the watermark past effects
nobody had sent. The kernel cannot tell such an acknowledgement from a real one — by design, since
it has no way to observe delivery.

So exactly-once holds only if the dispatcher is the only thing that proposes one. That is now a
stated property of the deployment rather than an accident: the HTTP API has no route that reaches
it, `SECURITY.md` says so, and the simulator injects the two *legitimate* misbehaviours — a stale
acknowledgement, which must be an accepted no-op, and one ahead of the log, which must be refused —
as faults instead.

## An effect in flight is part of the state

The subtler consequence, also found by a simulation. An `ArtifactCollected` sitting in the outbox is
an instruction to delete bytes that has not been carried out. Without accounting for it, this was
legal:

1. delete the last version referencing an artifact — a collection order is produced
2. re-ingest the same bytes — accepted, the artifact is present again
3. publish a new version against them — accepted
4. the collector finally runs and deletes the artifact the new version points at

So `Blob` records the sequence number of the order against it, and an artifact cannot come back
until the watermark has passed that number (`RejectionCode.COLLECTION_PENDING`). An artifact that is
`present` therefore always has `collectSeq == 0`, which the record's constructor enforces — the bad
state is not representable.

## Costs

**Delivery is asynchronous, so the outside world is behind.** By design, and it is why
`cairn_outbox_depth` is the metric `docs/operations.md` says to alert on: a registry whose outbox is
growing is one whose artifacts are not being collected and whose caches are not being invalidated,
and every other metric looks healthy while it happens.

**The outbox is unbounded.** A consumer that is down forever means an outbox that grows forever.
There is no backpressure in the kernel, because "refuse writes because a consumer is down" is a
policy decision a library should not make for an operator. The depth is exposed and the operations
guide says to alert on it, which is the honest version of not solving it.

**One extra log record per delivery batch.** One, not one per effect: the watermark is monotone, so
acknowledging only the highest of a run is equivalent.

## Rejected alternatives

**Kafka transactions, or a two-phase commit between the registry and the broker.** Genuinely
exactly-once, and it ties the registry's availability to the broker's and requires every consumer to
be a transactional Kafka consumer. The split above needs nothing from the consumer but one durable
integer.

**Emitting effects from the apply path but suppressing them during replay,** with a flag. This is
the fix somebody reaches for first, and it is the one that does not work: it makes "is this a
replay" a question the apply path has to answer, and it leaves the failed-publish half of the
problem completely untouched.

**A separate outbox table rather than the outbox being part of the state.** Standard, and it would
mean the outbox and the state could be written non-atomically. Here they are the same value, so
"the effect is durable if and only if the change that caused it is" needs no argument.
