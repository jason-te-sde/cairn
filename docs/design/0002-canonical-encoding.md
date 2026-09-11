# 2. One state, one byte string

`cairn-codec` produces a **canonical** encoding, not merely a deterministic one. Every map is
written in key order *and refused out of order*, every value has exactly one representation, and the
decoder rejects anything a conforming encoder could not have produced.

The consequence is that a SHA-256 over the encoding of a registry is an identity for that registry.

## Why canonicity rather than determinism

Determinism — the same state encodes the same way twice — would be enough for a log to replay. Four
things need the stronger property:

- **Comparing two replicas is 32 bytes** rather than a structural walk, which is why invariant I1
  is one assertion with a readable failure message.
- **`cairnctl verify`** re-derives the whole state from the log and compares it to what is being
  served, in one call. An end-to-end integrity audit is only this cheap because two states agree if
  and only if one hash matches.
- **The differential test** compares two independent implementations of the rules after every step
  by digest, so it catches a disagreement in a field nobody thought to write an assertion for.
- **No peer can produce a second valid encoding** of a state that compares as different. Refusing an
  unsorted map is what turns "our encoder happens to sort" into a property of the format.

## What the format replaces

The system this is derived from stored a version record as

```java
modelId + ":" + version + ":" + s3Bucket + ":" + s3Key + ":" + fileHash + ":" + fileSize
        + ":" + description
```

and read it back with `split(":", 7)`. The limit argument is the tell: somebody knew the last field
could contain a colon and made the last field safe. Two failures follow, and `ColonCodecTest` runs
both against a faithful reproduction:

1. **A colon in a middle field shifts every field after it.** `s3Key` is field four of seven, and
   object keys contain colons routinely — `models/fraud/2.1.0:final/model.pt` is an ordinary key.
   One colon there and `fileHash` holds the tail of the key, `Long.parseLong(fileSize)` is handed a
   hex string, and the record fails to parse. In the original that exception was caught and logged
   *inside the state machine's apply method*, so the command was silently dropped on that replica
   and applied on every other one. Two of the eight known flaws in `cairn-testkit` are really this
   one story.

2. **The read path joined eight fields and split seven.** The original's `get` appended `createdAt`
   to the same string and handed it to the same `split(":", 7)`, so `description` came back as
   `description + ":" + createdAt`. Nothing threw. Every record, every model, every read.

## Two independent defences

An identifier cannot contain a delimiter (`io.cairn.core.Names` refuses it at construction) **and**
there is no delimiter for it to contain (every field is length-prefixed). Either alone would be
enough. Both, because one defence that can be forgotten is not a defence — and because the fields
where arbitrary text *is* legal, like a label value, need the second one. A label value of
`s3://bucket/key:with:colons` round-trips exactly.

## What the decoder refuses

A decoder is the one component whose input is guaranteed to be attacker-influenced eventually, and a
permissive one is how a corrupt file becomes a process that allocates two gigabytes. The rejection
corpus in `CodecRejectionTest` covers: bad magic, a format version from the future, a bad checksum,
trailing bytes, truncation at *every* offset, a flip of *every* bit, a varint past ten bytes or with
a continuation bit on the tenth, a boolean outside `{0, 1}`, malformed UTF-8, a length or count
larger than the remaining input, an unsorted or duplicate-keyed map, an unknown tag, an identifier
that would be illegal, an outbox whose sequence numbers disagree with the watermark, and a registry
the kernel could not have produced.

Two of those are exhaustive rather than illustrative, because "we thought of the corruptions worth
testing" is a weaker claim than "every corruption in this class is covered".

## Costs

**Not human-readable.** A corrupt snapshot cannot be inspected with a text editor. Paid for by
`cairnctl state --at=N`, which renders any point in history, and by the log being the source of
truth rather than the snapshot.

**No forward compatibility.** A file written by a newer build is refused rather than read
optimistically, because reading it optimistically means silently dropping fields. Bumping the format
version is a deliberate act with a migration note in `CHANGELOG.md`.

**Strictness has a cost at the edges.** A client that sends a map in the wrong order gets an error
rather than a shrug. Since the only encoder is this one, that cost falls on nobody in practice — and
the day it does, the error is the right outcome.

## Rejected alternatives

**Protobuf.** Would have removed this module. Protobuf's wire format is explicitly *not* canonical:
field order and the encoding of defaults are both implementation choices, so a digest over the bytes
is a digest over the library version. Everything in the first section would have needed a different
mechanism.

**JSON for the log and snapshots.** Debuggable, and not canonical for the same reason, plus a
key-ordering question per language. It is used at the HTTP edge, where the audience is a person,
and not on disk, where the audience is a checksum.

**Varints everywhere, including a digest.** A digest is 32 bytes, always. Writing it as 64 hex
characters would have doubled the most frequent field in a snapshot and raised the question of
whether a stored digest might be uppercase. There is no case in binary.
