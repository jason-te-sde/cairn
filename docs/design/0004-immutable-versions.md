# 4. A published version is immutable, all of it

Once `fraud@2.1.0` exists, its artifact digest never changes. There is no command that rewrites it.
A republish with the same digest, parents and labels is an accepted **retry**; with anything
different it is `IMMUTABLE_VERSION` and the state is untouched. A deleted version leaves a
tombstone, and the identifier is never reusable.

## Why this rather than last-write-wins

A model registry exists to answer one question reliably: *what is `fraud@2.1.0`?* If the answer can
change, every artefact that quotes a version — a deployment manifest, an incident report, a model
card, an audit trail — is a reference to something that may since have become something else. The
guarantee is the product.

Mutability also makes an incident unanswerable. "We were serving 2.1.0 on the day of the outage" is
useless if 2.1.0 has since been replaced, and the replacement leaves no trace. With immutability,
"what was serving" and "what did it contain" are both answerable from the log forever.

## The whole record, not just the digest

Labels and declared parents are immutable too, and that is a deliberate strictness. A version's
lineage is a claim about how it was produced; letting somebody edit it later would make provenance a
mutable field, which is no provenance at all. Labels are metadata, and the argument is weaker — but
"mostly immutable" is a guarantee nobody can reason about, and there is a cheap alternative:
publish a new version.

What is *not* compared is `publishedBy` and `publishedAt`. Those record when the version came into
existence, not when somebody last retried telling us, and a retry on a flaky network legitimately
carries a different clock reading. `aRetryDoesNotRewriteWhoPublishedIt` is the test.

## Why a tombstone rather than a removal

Removing the entry would let a later publish reuse the version string with different content — so
immutability would hold only for versions nobody had deleted first. A guarantee with a loophole that
size is not a guarantee.

The tombstone also keeps lineage sound. Deleted versions are never removed, so an ancestor always
exists; and a version with *live* descendants cannot be deleted at all (`HAS_DESCENDANTS`), so a
live version's parents are always live too. Both halves are invariant I9.

## What is mutable, and where the line is

| | |
| --- | --- |
| The artifact, parents, labels | never change |
| The stage | changes, through a legal transition only |
| Deleted | changes once, and is terminal |

`Stage` is the mutable part because "what should serving load" is a decision people revisit, and a
release is not a new model. The transition relation is data rather than a chain of `if` statements
so the illegal moves are as visible as the legal ones, and `StageTest` walks the whole table.

Two properties of that relation are enforced rather than hoped for. Promotion to `PRODUCTION`
demotes the incumbent **in the same transition**, so there is never an instant with two production
versions — as two commands it would be two log entries, and between them the registry would hold
two, or none. And `DEPRECATED` is terminal, so "we have stopped supporting this" cannot be quietly
undone.

## Costs

**A typo in a version string is permanent.** Publish `2.1.0` when you meant `2.0.1` and the only
remedy is to delete it, which leaves a tombstone and burns the identifier. That is the intended
trade: a registry where mistakes can be erased is a registry where history can be.

**No amendments.** Correcting a label means a new version. For a registry whose versions are
releases, that is the correct workflow; for one being used as a scratch database, it is friction —
and `0005-scope.md` is explicit that this is not a scratch database.

**Storage grows monotonically in metadata.** Tombstones are never removed. A version record is a
few hundred bytes and artifacts are reference-counted and collected, so the metadata is the small
part; `docs/operations.md` has the sizing.

## Rejected alternatives

**Mutable versions with an audit log.** What most registries do. It puts the guarantee in a second
table that can disagree with the first, and answers "what did this contain" with "let me reconstruct
it" rather than "that is what it is".

**Content-addressed versions — name a version by its digest and drop the identifier.** Perfectly
immutable and unusable: people need to say "2.1.0". The digest is still the artifact's only name;
the version identifier is a human handle bound to it once.

**Allowing a republish to overwrite when nothing references the version yet.** Tempting, and it
makes the guarantee conditional on a property that changes over time. `Flaw.OVERWRITE_ON_REPUBLISH`
is the unconditional version of this mistake, and invariant I2 catches it.
