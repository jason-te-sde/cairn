# Testing

528 tests. 520 run on every build; 8 are benchmarks, off by default. 87.0% line and 80.0% branch
coverage.

Coverage is a smoke alarm, not a goal. The untested remainder is mostly `toString`, unreachable
defensive branches, and `Main`'s flag handling; a number near 100% usually means somebody wrote
tests for `toString`.

## The five layers

New code lands in the cheapest layer that can catch its bugs.

| Layer | Where | Covers |
| --- | --- | --- |
| **Unit** | each module's `src/test/java` | one method, one transition, one rejection |
| **Rejection corpus** | `cairn-codec`, `cairn-server` | what a decoder and a parser must refuse |
| **Differential** | `cairn-testkit`, `cairn-store` | two implementations of the same contract, required to agree |
| **Simulation** | `cairn-testkit` | a whole registry from one seed, invariants after every step |
| **Integration** | `cairn-server` | real sockets, real files, real restarts |

## The twelve properties

Checked after **every step**, not at the end of a run. A registry that briefly holds two production
versions and then settles looks perfectly healthy by the time a run finishes, and an end-state
assertion would pass.

| | Property | Means |
| --- | --- | --- |
| I1 | Convergence | replicas fed the same log hold byte-identical state |
| I2 | Version immutability | a published version's artifact digest never changes |
| I3 | Reference integrity | every blob's reference count equals the live versions pointing at it |
| I4 | Collection safety | an artifact is absent only when unreferenced, and nothing live points at one with an undelivered collection order |
| I5 | Stage exclusivity | at most one production version per model; no tombstone in production |
| I6 | Effect log integrity | the outbox is contiguous from the watermark; the watermark only rises |
| I7 | Exactly-once delivery | the downstream world applied each acknowledged effect once, in order |
| I8 | Monotonicity | no replica's applied index goes backwards, including across a crash |
| I9 | Lineage integrity | every declared ancestor exists; live versions have live parents; no cycles |
| I10 | Rejection purity | a refused command changes nothing but the log position |
| I11 | Artifact integrity | the bytes stored for a present artifact hash to its digest |
| I12 | Downstream agreement | once delivery catches up, the outside world agrees about what is in production |

I1–I10 are checked from a `RegistryView`, so they work against *any* implementation of the rules.
That is what makes it possible to point them at implementations with known bugs and confirm they go
red. I11 and I12 are about the system around the state — the blob store's bytes and a consumer's
beliefs.

Several are claims about history rather than about an instant, which is why `Invariants` is stateful:
it remembers every digest each version has ever had, so it can notice one changing, and the highest
applied index per replica, so it can notice durability going backwards.

## The checker is itself checked

`InvariantsTest` is 26 tests. Half build states by hand — including states no kernel can produce —
and require the right property to fail *by name*. The other half is the important half: they require
it **not** to fire on states that look suspicious and are legal.

- A replica replaying its log passes through every index again on its way back. A naive monotonicity
  check fires on every recovery.
- An artifact legitimately re-ingested after collection. The checker tells it apart from an artifact
  resurrecting itself by requiring the collection order to have been delivered first.
- A tombstone with a deleted ancestor. That is history, not a dangling pointer; only a *live* version
  with a deleted parent is a violation.
- Delivery running ahead of the watermark. That is the crash window the design tolerates, and a
  checker that insisted the two match would fail on every healthy run.

A checker that fired on those would be useless under exactly the conditions it exists for.

## Proof that the suite can go red

`FlawTest` reintroduces eight specific defects and asserts that the check named in
`Flaw.caughtBy()` is the one that fails. The mapping is asserted rather than documented, so a check
that stops working fails at the flaw it was supposed to catch rather than passing quietly.

**Five are defects the system this project is derived from actually shipped.**

| Flaw | Inherited | Caught by |
| --- | :---: | --- |
| A colon-delimited record format | ✓ | `ColonCodecTest`, directly |
| Effects published from inside apply | ✓ | I7, after a replay |
| An apply failure swallowed and logged | ✓ | I1, as replica divergence |
| A restore that aliases live state to the snapshot | ✓ | `Recovery` refusing to start |
| A client-supplied digest stored unverified | ✓ | I11 |
| A republish that overwrites | | I2 |
| An artifact collected without counting references | | `Blob`'s constructor |
| A consumer watermark advanced before delivery | | I7 |

Two of those entries are more interesting than expected, and the file says so rather than quietly
picking a check that worked:

- **The snapshot-aliasing defect is survivable on its own.** A snapshot that under-reports its own
  index costs a longer replay and nothing else, because the log still holds the records. It becomes
  data loss only once the log is compacted on the strength of that snapshot — and then recovery
  refuses to start, which is the correct outcome and a better one than an invariant firing later.
  The first version of the test asserted I8 Monotonicity and never fired.
- **Collecting without counting is caught by a record's constructor**, not by an invariant. `Blob`
  refuses to represent an absent artifact that live versions still reference, so the bug fails at the
  line that caused it with a stack trace naming the caller. A bug caught by making the bad state
  unrepresentable is a better outcome than one caught by a test.

`everyFlawHasATest` asserts the enum and this file cannot drift apart.

## Differential testing

Two pairs, for two different contracts.

**The kernel against a second implementation of the rules.** `ReferenceKernel` keeps versions in one
flat map keyed by `model@version` rather than nested per model, edits mutable state in place, and
keeps **no reference counts at all** — it recomputes them by scanning every version whenever its
state is observed. Every step compares outcomes, effects and the canonical state digest, so the
kernel's incrementally maintained counts are checked against a full recount on every state a run
reaches. The delete path is where this class of registry goes wrong.

The honest limit: the reference deliberately mirrors the *order* of validation, because the test
compares rejection codes and a command can violate two rules at once. So it proves the two agree
about what each rule does, not that the rules are in the right order.

**The file log against the in-memory log.** This found a real problem on its first run. The port's
contract said `discardThrough` "discards every record at or below" an index; the in-memory log
honoured it exactly, the segmented one can only free whole files, and the two disagreed about
`firstIndex()` on the first seed that discarded anything. **The contract was wrong**, not either
implementation — an append-only log cannot free records at an arbitrary boundary without rewriting a
file, and rewriting is the one thing it does not do. The wording now states what is guaranteed (no
record *above* the index is discarded; read from `firstIndex()`), and the test asserts that instead.

## What the simulator injects

One agreed log, several replicas applying it, and faults between them. A 400-step run at seed 1
reports, typically:

```
accepted=84 idempotent=115 rejected=238 (11 distinct codes)
crashes=13 powerLosses=6 checkpoints=18 prefixesReleased=6
duplicateDeliveries=4 sinkRefusals=117 dispatcherRestarts=10
deliveredThenDied=3 redeliveries=4 collections=13 corruptUploads=…
effects=55/55
```

| Fault | What it models |
| --- | --- |
| Replica crash | in-memory state lost; rebuilt from its own snapshot plus the log |
| Power loss before sync | an append that reached the log and not the disk |
| Checkpoint | a snapshot, and a log prefix released only once every replica could rebuild without it |
| Duplicate delivery | a log entry applied twice — which must be a no-op |
| Consumer refusal, then recovery | a downstream world that is down and comes back |
| Dispatcher restart | a dispatcher that has forgotten what it sent |
| **Crash between a delivery and its acknowledgement** | the window the whole design exists for |
| Stale acknowledgement | a dispatcher from a previous life; must be an accepted no-op |
| Acknowledgement ahead of the log | a confused dispatcher; must be refused |
| Corrupt upload | bytes that do not match the digest offered for them; must be refused, and nothing recorded |

The retention policy uses what each replica **believes** it snapshotted, not what its store actually
wrote. That is faithful to a real node, which compacts on the strength of its own belief — and it is
the difference between the snapshot-aliasing defect being survivable and being data loss.

## Rules that are actually enforced

- **No test sleeps waiting for progress.** The deterministic layers have no clock; the integration
  tests poll a condition with a deadline. A loaded CI runner makes a test slower, never flakier.
- **Anything randomized takes a seed and prints it on failure.** A red test that cannot be replayed
  is close to worthless.
- **Assertions carry a message naming the state**, not just the expected value.
- **Every rejection test asserts the state did not change.** "It said no" and "it said no and changed
  nothing" are different claims, and only the second is useful.
- **Chaos tests assert the run was hostile.** Per-seed floors for what always happens; aggregate
  assertions in the soak for the rare faults. Requiring a rare fault per seed would be requiring a
  coincidence; not requiring it anywhere would be hoping the path was covered.
- **The soak asserts every rejection code was reached.** Adding a rule without adding a way to reach
  it fails there.

## Reproducing a failure

Simulation failures print the seed and the step:

```
I5 Stage exclusivity violated at step 412 (seed 8123): fraud has 2 production versions: [...]
```

That seed reproduces the run exactly, including every fault decision. Add it to the list in
`SimTest` so it is replayed on every build afterwards.

```bash
mvn test -pl cairn-testkit -am -Dtest=SimTest -Dsurefire.failIfNoSpecifiedTests=false

mvn install -DskipTests
mvn test -pl cairn-testkit -am -Dtest=SoakTest -Dcairn.sim.seeds=2000 \
    -Dsurefire.failIfNoSpecifiedTests=false
```

The suite runs 60 seeds; every pull request runs 300; the nightly workflow runs 5,000. A 1,000-seed
sweep is 3.17 million invariant checks in about thirteen seconds.

`-am` is not optional in those commands. Without it the reactor holds one module and no parent, and
the enforcer's `reactorModuleConvergence` rule refuses to run — which is a confusing failure to hit
while chasing a test, so it is worth knowing that it is the build and not the test.

## What this suite does not cover

Stated because a testing document that only lists strengths is marketing.

- **Consensus is not tested, because it is not implemented.** The simulator models one agreed log.
  It cannot model two replicas being fed *different* logs, because nothing here decides what the log
  is. See `design/0005-scope.md`.
- **The simulated disk models sync boundaries, not the disk.** Unsynced writes vanish on a crash,
  which is the property the design depends on, but there is no torn-sector or reordered-write
  injection. The real log's recovery is tested separately by truncating and corrupting actual files —
  every truncation and every single-bit flip of a snapshot — but that is a different thing from
  injecting one mid-run.
- **Crash injection lands between steps, not between instructions.** A crash halfway through
  `Kernel.apply` is not reachable, and does not need to be: the function returns a new value, so
  there is no intermediate state to crash in. A crash halfway through a *file write* is reachable
  and is not injected mid-run.
- **The blob store is not tested under concurrent writers of the same digest.** Two uploads of the
  same bytes race on an atomic rename, which is safe, and the test suite asserts deduplication
  serially rather than concurrently.
- **The HTTP layer is not load tested.** `com.sun.net.httpserver` is thread-per-request and the
  README says so; there is no measurement of where it stops coping.
- **No fuzzing beyond the structured corpus.** The codec gets every bit flip of a real snapshot and a
  hand-built rejection corpus; it does not get a coverage-guided fuzzer.
- **The container path is only ever exercised by CI, never locally.** The `container` job in
  `ci.yml` builds the image and drives a real registry through publish, promote, read-back, a
  refused republish, a metrics scrape without the publish token, and a `SIGKILL` restart that has
  to come back with an identical state digest — it does, `ready index=7 state=bb354c33914b` before
  and after. So the path is verified, but it cannot be reproduced on the machine this was written
  on: the Docker daemon starts and the registry is unreachable, so `docker pull alpine:3` hangs and
  no base image can be fetched.

  The practical consequence is that a change to the `Dockerfile` or the compose file cannot be
  checked by `preflight.sh` and will only be validated after a push. That is a gap in the local
  loop rather than in the coverage, and it is worth knowing before editing either file.
