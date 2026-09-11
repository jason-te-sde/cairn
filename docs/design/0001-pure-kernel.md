# 1. The rules are a pure function

`cairn-core` has no dependencies, no threads, no clock, no I/O, no randomness and no logging. The
whole of it is one function:

```java
Transition apply(Registry state, long index, Command command)
```

Everything that varies between two runs of the same command sequence arrives as an argument: the
timestamp, the actor, the log index. Nothing is read from the environment.

## Why

The state machine this project is derived from was a class that held a RocksDB handle and a Kafka
producer. It took a `byte[]`, split it on colons, wrote to RocksDB, published an event, and wrapped
the whole thing in `catch (Exception e) { LOG.error(...) }`. Each of those is a defect on its own.
Together they make the class untestable, because there is no way to ask it what it would do — you
can only run it and look at the world afterwards.

Here the answer to "what would it do" is the return value. That is what makes the rest of the test
suite possible:

- A **simulator** can drive a whole registry, its replicas, its disks and its consumers from one
  integer seed, because there is nothing else for the behaviour to depend on. A failure at seed
  8123 is the same failure at seed 8123 tomorrow, on another machine, on another JDK.
- A **differential test** can run the same commands through a second implementation and compare,
  because the comparison is over values rather than over two databases.
- **Invariants** can be checked after every single step rather than at the end of a run, because
  checking is free: it is a function of a value that is already in hand.
- **Log replay** is a fold, which is why `cairnctl state --at=N` is a library call rather than a
  feature with its own code path. Recovery at startup and the answer to "how did this version get
  into production" are the same three lines stopped at different points, so there is no second
  implementation to disagree with the first.

## The module boundary is the enforcement

Discipline decays. `cairn-core`'s POM has an empty dependency list, so there is nothing on its
compile path to drift towards: the kernel cannot reach a disk or a clock even by accident, because
neither is importable. That boundary is worth more than a comment asking people not to.

## Costs

**Allocation.** Every command copies the maps on the path it touched. Measured: 2.4 million commands
a second on an M-series laptop for a realistic mix. That is not the interesting number, though — see
below.

**A real scaling limit.** `Model` holds its versions in a sorted map and returns a new model per
publish, so publishing into a model that already has *N* versions copies *N* entries, and publishing
*N* versions into one model is O(N²) overall. Measured:

| Versions in one model | Publishes per second |
| --- | --- |
| 100 | 235,000 |
| 1,000 | 65,000 |
| 5,000 | 17,300 |
| 10,000 | 9,200 |

Ten thousand versions of one model spread across two hundred models is 43,000 publishes a second,
which is the shape that scales. This was found by a benchmark rather than by reasoning: a recovery
measurement came back at three thousand records a second, the log turned out to be fine, and the
cost was here. `KernelScalingTest` is the measurement and `0005-scope.md` records the fix that is
not implemented.

**No incremental computation.** Anything derived has to be recomputed or cached outside the kernel.
`Model.production()` walks the version map rather than reading a cached pointer, which is a
deliberate choice explained in that class: a denormalized pointer is a second place the answer
lives, and "the cached copy drifted" is the most common way a registry ends up serving a model
nobody promoted.

## Rejected alternatives

**A mutable state machine with a lock.** Faster, and it makes every property above unavailable. In
particular a validation failure can leave a partial change behind, which is the failure mode
invariant I10 exists to rule out — and in a mutable implementation I10 is a thing to hope for rather
than a thing that is structurally true.

**Letting the kernel read the clock and drop the timestamp argument.** Two lines shorter at every
call site. It would also mean every replica wrote a different `publishedAt` for the same command,
and the replicas would have diverged in a field nobody thinks of as state. The simulator would
never find it, because the simulator would be equally non-deterministic.

**A persistent (structurally shared) sorted map, to remove the O(N) copy.** The right fix for the
scaling limit above, and not taken: writing a correct HAMT or finger tree with no dependencies is a
data-structure project, and a registry's shape is hundreds of versions per model rather than tens of
thousands. The limit is measured, documented, and the first thing to revisit if that stops being
true.
