# Changelog

Kept by hand from conventional commits. Versions follow [semantic versioning](https://semver.org),
with the additional rule that **the on-disk format version is separate and is called out
explicitly**: a change to it is a change a reader of the old format has to be told about, whatever
the project version says.

## [0.1.0] — 2026-09-11

First release. On-disk format version **1**.

### The registry

- **A pure kernel.** Five commands, one function, no threads, no clock, no I/O, no randomness, and
  no dependencies. `docs/design/0001-pure-kernel.md`.
- **Immutable versions.** A published version's artifact, parents and labels never change. A
  republish with identical content is an accepted retry; with anything different it is refused. A
  deleted version leaves a tombstone and its identifier is never reusable.
- **Content-addressed artifacts.** A digest is computed from the bytes while they are written, never
  taken from the caller. Artifacts are reference-counted across versions and collected when the last
  live reference goes.
- **Lineage.** A version declares its ancestors; every ancestor must exist and be live, and a version
  with live descendants cannot be deleted. Provenance never dangles.
- **Stages,** with a transition relation that is data rather than a chain of conditionals. Promotion
  to production demotes the incumbent in the *same* transition, so there is never an instant with
  two production versions.
- **Effects as values.** Nothing is published from inside the apply path. Committed effects go to a
  replicated outbox with contiguous sequence numbers, are delivered at least once by a dispatcher,
  and are applied at most once by a consumer that remembers a watermark.
  `docs/design/0003-effects-are-values.md`.

### Durability

- A crash-recoverable segmented command log: checksummed records, torn tails survivable, prefix
  release after a snapshot.
- Snapshots written to a temporary name, forced, moved atomically, and pruned to the most recent
  two. A damaged snapshot is skipped in favour of an older one rather than refusing to start.
- A content-addressed blob store with atomic placement, deduplication, and partial-upload cleanup
  at startup.
- `Recovery.checkpoint` is the single place the snapshot-then-discard ordering is stated.

### Running it

- An HTTP API, Prometheus metrics, `/healthz` and `/readyz` answering different questions, and
  `cairnctl`.
- Bearer tokens, with a separate admin token for stage changes and deletions.
- Secure by default: a non-loopback bind without a token is refused unless `--insecure` is passed.
- `cairnctl verify` re-derives the whole state from the log and compares it by canonical digest.
- `cairnctl state --at=N` reconstructs the registry as it was at any log index, using the same code
  path recovery uses.
- A container image and a compose stack, both exercised by CI.

### Testing

- 513 tests, 505 on every build. 87.0% line and 79.9% branch coverage.
- Twelve invariants, checked after every step of a seeded simulation.
- Eight known defects reintroducible on purpose — five of them inherited from the system this
  project is derived from — with the check that catches each one asserted rather than documented.
- Differential testing in two places: the kernel against an independent second implementation of
  the rules, and the file log against the in-memory one.
- A rejection corpus for the codec covering every single-bit flip and every truncation of a real
  snapshot.

### Found while building this

Recorded because a changelog that only lists features is a marketing document. The README has the
full list with what caught each one.

- `svarint` overflowed into the sign bit for values of large magnitude, so the unsigned writer
  refused a correct encoding. Found by a boundary sweep, before the codec had ever been used.
- A defensive map copy inherited the source map's comparator, so a caller holding a reverse-ordered
  `SortedMap` would have got a reverse-ordered registry — and the canonical encoding assumes
  ascending keys.
- `CommandLog.discardThrough`'s contract promised more than a segmented log can deliver. Found by a
  differential test against the in-memory implementation; the contract was wrong, not either
  implementation.
- An `ArtifactCollected` effect still in the outbox could delete bytes that had legitimately been
  re-ingested in the meantime. Found by thinking through the simulator's end-to-end model; fixed by
  making the pending order part of the artifact's state, which is now
  `RejectionCode.COLLECTION_PENDING`.
- `AckEffects` turned out to be a privileged command: an acknowledgement from something that had not
  delivered anything silently discards effects. Found by a simulation failing invariant I7. Now a
  stated property of the deployment, with no HTTP route that reaches it.
- Segment reads issued two syscalls per record, so a batched replay was quadratic. Found by a
  benchmark; the same benchmark then showed the *remaining* cost was not in the log at all but in
  the kernel's per-model map copy, which is now measured as a curve and documented as a limit.

### Not implemented, on purpose

Consensus (this is the state machine, not the protocol), a persistent sorted map, outbox
backpressure, semantic version ordering, multi-tenancy, TLS, rate limiting, and encryption at rest.
`docs/design/0005-scope.md` gives the reasoning for each, and names what was deliberately *not*
taken from the system this project is derived from.
