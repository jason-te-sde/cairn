# Security

## Reporting

Use [GitHub's private advisory form](https://github.com/jason-te-sde/cairn/security/advisories/new).
Please do not open a public issue.

This is a personal project with no service-level commitment. Expect an acknowledgement within a
week.

## What this project is, in security terms

A model registry decides what your serving infrastructure loads. Write access to it is close to
remote code execution on everything downstream, and it should be treated that way — more carefully
than the code below actually justifies.

## What it defends against

**A lie about an artifact's contents.** A digest is computed from the bytes as they stream to disk,
never taken from the caller. `putVerified` compares and refuses; a mismatch stores nothing and
records nothing. This is the one guarantee the project is built around, and it is checked by
invariant I11 on every step of every simulation.

**A change to what a published version means.** Immutable, with no command that rewrites it. An
attacker who can publish cannot replace `fraud@2.1.0` with different weights — they can only publish
a new version, which is visible.

**A malformed or hostile encoding.** The decoder refuses anything a conforming encoder could not
have produced: bad checksums, truncation, oversized lengths, malformed UTF-8, unsorted maps, unknown
tags. Lengths are bounded before anything is allocated, so a corrupt file is an exception rather
than an `OutOfMemoryError`. `docs/testing.md` describes the corpus, which includes every single-bit
flip of a real snapshot.

**A malformed request body.** The JSON parser refuses duplicate keys, which is a real hazard rather
than pedantry: two parsers can disagree about which value wins, so one request means one thing to a
proxy and another to the service behind it. Depth and size are bounded.

**Exposure by accident.** A node refuses to bind a non-loopback address without a client token
unless `--insecure` is passed explicitly.

**Privilege confusion between publishing and releasing.** `--admin-token` is separate and required
for stage changes, deletions and `fsck`. Ejecting whatever is in production is not the same
privilege as publishing something nobody is using yet. The two tokens must differ, and the server
refuses to start if they do not.

**Timing attacks on a token.** Compared with `MessageDigest.isEqual`, so a token cannot be recovered
one character at a time.

**Log injection through a name.** Identifiers cannot contain control characters, and a *rejected*
identifier — the one path where a hostile string is guaranteed to reach a log — is escaped before it
is interpolated into the error message.

**A response that breaks a consumer.** JSON output escapes every control character plus U+2028 and
U+2029, which are legal in a JSON string and illegal in a JavaScript string literal.

## What it does not defend against

Stated plainly, because a security document that only lists strengths is marketing.

**No transport security.** Plain HTTP. Tokens travel in a header in the clear. Terminate TLS in
front of it — `docs/operations.md` says so and `design/0005-scope.md` says why it is not built in.

**No encryption at rest.** Artifacts and metadata are plain files. Anybody with filesystem access
has everything, and can rewrite the log.

**No identity model.** Two shared secrets. No users, no roles, no per-model permissions, no audit of
*who* beyond a self-declared `actor` string that nothing verifies. In front of anything real, this
belongs behind a gateway that has an identity model.

**No rate limiting.** A client with a valid token can fill the log and the disk. `--max-artifact-bytes`
bounds a single upload — and should be set, because its default is the largest artifact the registry
can represent rather than a number anybody chose — but nothing bounds the *number* of uploads.

**The `actor` field is not authentication.** It is whatever the client said. It is recorded because
an unverified attribution is still useful when reconstructing an incident, and it is not evidence.

**`AckEffects` is privileged, and that is a deployment property rather than a mechanism.** Anything
that can propose one can silently advance the delivery watermark past effects nobody delivered,
which discards them. The HTTP API has no route that reaches it and the dispatcher is in-process, so
the only way to reach it is to have the log — at which point you can write anything anyway. This is
documented rather than enforced, because the kernel genuinely cannot tell a rogue acknowledgement
from a real one; it is recorded here because a simulation found it and because anyone embedding the
kernel needs to know.

**Orphaned artifacts are reported, never deleted automatically.** Bytes an authenticated client
uploaded and never published stay on disk until somebody runs `fsck` and acts on it. That is a
storage-exhaustion vector, and it is the safe end of the trade: a sweep that deleted whatever it
could not find a reference for would race every upload in flight.

**Crash faults, not Byzantine ones.** The design assumes storage and consumers fail by stopping, not
by lying. A blob store that returns different bytes than were written is outside the model — though
`cairnctl verify` and invariant I11 would notice.

## Supported versions

Pre-1.0. The most recent release only.
