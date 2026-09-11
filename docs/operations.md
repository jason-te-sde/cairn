# Running it

Written for three in the morning. The symptom table is at the bottom.

## Starting it

```bash
cairnd --data-dir=/var/lib/cairn --address=0.0.0.0 --port=9080 \
       --token="$CAIRN_TOKEN" --admin-token="$CAIRN_ADMIN_TOKEN" \
       --effects-log=/var/lib/cairn/effects.jsonl
```

**A node refuses to bind a non-loopback address without `--token`** unless `--insecure` is passed.
That is deliberate. A model registry decides what your serving tier loads, so an unauthenticated one
is a remote code execution primitive with extra steps. A laptop stays one command; exposing it
becomes something you have to mean.

| Flag | |
| --- | --- |
| `--config=<file>` | properties file; flags override it |
| `--data-dir=<path>` | log, snapshots and artifacts (`cairn-data`) |
| `--address` / `--port` | what to bind (`127.0.0.1:9080`; port 0 for ephemeral) |
| `--token` | bearer token for reads and publishes |
| `--admin-token` | bearer token for stage changes, deletions and `fsck` |
| `--durability` | `SYNC_EACH` \| `SYNC_ON_DEMAND` \| `NONE` (`SYNC_EACH`) |
| `--snapshot-every` | commands between snapshots, 0 to disable (`1000`) |
| `--effects-log` | append delivered effects as JSON lines |
| `--dispatch-every-millis` | retry interval for a stalled consumer (`2000`) |

An unknown flag is an error rather than a shrug, because a typo in a flag that silently does nothing
is how a node ends up running without the setting somebody thought they had applied.

## What to alert on

### `cairn_outbox_depth` growing

**This is the one.** Effects produced and not yet delivered. A registry whose outbox is growing is
one whose artifacts are not being collected and whose caches are not being invalidated — and every
other metric looks healthy while it happens. Nothing is lost; the outbox is durable. But the outside
world is drifting away from the registry, and it will keep drifting.

```
alert: cairn_outbox_depth > 100 for 10m
```

What to do: look at the consumer. `cairn_dispatch_failures_total` rising alongside means the sink is
refusing; flat means it is not being called, which points at the dispatcher thread. `cairnctl
effects` lists what is owed and `--json` gives you the sequence numbers.

### `cairn_dispatch_failures_total` rising

The consumer is refusing deliveries. Expected during a downstream outage; the dispatcher retries
every `--dispatch-every-millis` and the outbox holds everything in the meantime. Only worth paging
on in combination with the depth alert above.

### `cairn_effects_deduplicated_total` stepping up

Redeliveries a consumer discarded. **Not an error** — it counts crashes that landed between a
delivery and its acknowledgement, which the system handles correctly and which nothing else can see.
A step change means something is restarting. Worth a dashboard, not a page.

### `cairn_log_last_index - cairn_snapshot_index` growing without bound

Snapshots are not being written. Recovery still works; it just takes longer, linearly. Check the
logs for `could not write a snapshot`, and check free space.

### Nothing at all from `/readyz`

The kernel thread is not responding. See the symptom table.

## Disk sizing

Three things grow, at very different rates.

**Artifacts** dominate, and they are the reason reference counting exists. An artifact is stored once
however many versions point at it, and is deleted when the last live version releases it — so the
steady-state size is the sum of *distinct* model weights currently referenced, not the sum over
versions. `cairn_artifact_bytes` is the number.

**The command log** grows by roughly 60–120 bytes per command and is released below each snapshot, so
it settles at about `--snapshot-every` records plus one segment (8 MiB). With the default of 1,000
that is a few megabytes. `cairn_log_bytes`.

**Metadata** grows monotonically, because tombstones are never removed — see
`design/0004-immutable-versions.md`. A version record is a few hundred bytes. A registry with a
hundred thousand versions ever published has a snapshot in the tens of megabytes, which is not the
number to worry about.

Rule of thumb: size for artifacts, add a gigabyte, stop thinking about it.

## Backup and restore

The data directory is the whole registry. Three things in it, with different needs:

```
/var/lib/cairn/log/          append-only; snapshot-and-copy is safe
/var/lib/cairn/snapshots/    written atomically; safe to copy at any time
/var/lib/cairn/artifacts/    content-addressed; immutable once written
```

**Hot backup** works without stopping the registry, because nothing in there is ever rewritten in
place: log segments are append-only, snapshots are moved into place atomically, and an artifact's
path is its digest. A `tar` or an `rsync` while running produces a directory that recovers to *some*
valid point — possibly missing the last few commands, which is the same guarantee a replica would
have.

**For a backup that is exactly a point in time**, stop the registry first. A clean shutdown drains
the outbox, so what you get back is not owing anything downstream.

**Restore** is copying the directory back and starting. Then, always:

```bash
cairnctl verify
```

That re-derives the entire state from the log and compares it to what is being served, by canonical
digest. It is the one command worth running after any restore, any migration, and any incident.
Exit 0 means the registry is what the log says it is.

**A restore from an old backup is the one dangerous case.** If the log in the backup is *behind* a
snapshot that survived, recovery refuses to start with `records that were acknowledged are missing` —
which is correct and is telling you the backup is inconsistent. Restore the whole directory
together, never one subdirectory of it.

## Upgrades

A rolling upgrade of a single-writer registry is a stop and a start. Order:

1. `cairnctl effects` — if the depth is non-zero, let it drain first, or accept that the new process
   will deliver what is owed on startup (which is fine; it is the same outbox).
2. Stop the old process. A clean shutdown drains the outbox and closes the log.
3. Start the new one.
4. `cairnctl verify`.

**Downgrades are only safe within an on-disk format version.** The format version is in every
snapshot and every log segment header, and a build refuses a version it does not know rather than
reading it optimistically. `CHANGELOG.md` says when it changes and what to do.

## Effects, and integrating a real consumer

The default consumer does two things: it deletes collected artifacts, and — with `--effects-log` —
appends every delivered effect to a file as JSON lines:

```json
{"sequence":6,"model":"fraud","type":"stage_changed","ref":"fraud@1.0.0","from":"production","to":"archived","actor":"alice","at":1789120582615}
{"sequence":7,"model":"fraud","type":"stage_changed","ref":"fraud@2.0.0","from":"staging","to":"production","actor":"alice","at":1789120582615}
{"sequence":8,"model":"fraud","type":"production_changed","production":"2.0.0"}
```

Note the order: the demotion arrives *before* the promotion, and the summary after both. That is
guaranteed, not incidental — a consumer replaying this stream never holds two production versions.

**To write your own consumer**, implement `EffectSink` and wrap it in `IdempotentSink` with a
durable `AppliedLedger`. The contract is: you are told about an effect **at least once**, and the
sequence number is what you deduplicate on. `FileAppliedLedger` is seventeen bytes on disk and does
this correctly; the ordering inside it — apply, *then* record — is the part that matters, and
getting it backwards loses effects silently.

**`AckEffects` is privileged.** Nothing but the dispatcher may propose it, and the HTTP API has no
route that reaches it. An acknowledgement from something that has not delivered anything moves the
watermark past effects nobody sent, and the registry cannot tell the difference. See
`SECURITY.md`.

## Orphaned artifacts

```bash
cairnctl fsck
```

Lists artifacts on disk the registry has no live reference for, and **deletes nothing**. The
direction of that comparison is the point: bytes are written before the command that records them,
so a sweep that deleted whatever it could not find a reference for would race every upload in
flight.

Orphans are normal in small numbers — an upload that was never published, or one whose `IngestBlob`
was refused. They are content-addressed, so an orphan is harmless and is reclaimed by the next
publish of the same bytes. If the total is large enough to matter, stop the registry, re-run
`fsck`, and delete the listed digests by hand with nothing writing.

## Tuning durability

Measured on an Apple M-series laptop, APFS:

| Mode | Appends per second | What survives a power loss |
| --- | --- | --- |
| `SYNC_EACH` | **364** | every command that was answered |
| `SYNC_ON_DEMAND` | 279,863 | everything up to the last explicit sync |
| `NONE` | 507,438 | nothing is promised |

An fsync costs about 2.7 ms here, so `SYNC_EACH` is bounded by the disk and the other two by the
CPU. A registry sees a handful of publishes a day, so **stay on `SYNC_EACH`**. The other modes exist
for a bulk import, and for benchmarks that are measuring something else.

## Symptom to cause

| Symptom | Likely cause | What to do |
| --- | --- | --- |
| Refuses to start: `records that were acknowledged are missing` | the log has been trimmed past what any usable snapshot covers — a partial restore, or a snapshot directory restored without its log | restore the whole data directory together. Do not delete the snapshots to "fix" it; that discards the state they describe. |
| Refuses to start: `is not a cairn log segment` | a foreign file in `log/`, or a truncated header | move the offending file aside; the message names it |
| Refuses to start: `log is not contiguous` | a log segment is missing | restore it. Everything from that index on is unreachable without it. |
| Refuses to start: `holds a checksum-valid record that is not a command` | something other than cairn wrote into the log file | this is not a torn write and is not recoverable by truncation; investigate what wrote there |
| Starts, logs `snapshot ... is damaged and will be skipped` | a snapshot came back failing its checksum | nothing to do; it fell back to an older one and replayed. Worth investigating the disk. |
| Starts, logs `truncated ... the tail was not durable` | a crash mid-append | normal and expected. Nothing was acknowledged past that point. |
| `/readyz` hangs, `/healthz` answers | the kernel thread is blocked — almost always the disk | check I/O wait and free space. The command path fsyncs; a disk that has stopped acknowledging writes stops the registry. |
| Everything is 401 | `--token` is set and the client is not sending it | `CAIRN_TOKEN`, or `--token=` |
| `DELETE` and stage changes are 401, reads work | that is the design: they need `--admin-token` | `CAIRN_ADMIN_TOKEN` |
| Publishing returns 503 `collection_pending` | the artifact's bytes are being collected and cannot come back yet | wait for `cairn_effects_dispatched_through` to pass the sequence number in the message, then retry. If it is not advancing, the consumer is stuck — see the outbox alert. |
| Publishing returns 409 `immutable_version` | that version exists with different content | publish a new version. This is working correctly. |
| Publishing returns 422 `digest_mismatch` | the bytes did not hash to the promised digest | a truncated upload or a mangled proxy. Nothing was stored. Retry. |
| Deleting returns 409 `has_descendants` | another live version declares this one as a parent | delete the descendant first, or archive this one instead |
| `cairnctl verify` reports `MISMATCH` | the served state and the log disagree | **page somebody.** This should be impossible; the log is the truth, so restart to re-derive from it, and keep the data directory for analysis. |
| Disk full | artifacts | `cairnctl fsck`, then look at what is referenced. The log and snapshots are megabytes. |

## What this does not do

- **No consensus.** One writer. High availability means a failover with shared storage, or putting
  the kernel behind a Raft group — see `design/0005-scope.md`.
- **No TLS.** Terminate it in front.
- **No rate limiting.** A client can fill the log.
- **No encryption at rest.** Artifacts and metadata are plain files.
- **No backpressure on the outbox.** A consumer that is down forever means an outbox that grows
  forever. Alert on the depth; that is the whole mitigation.
