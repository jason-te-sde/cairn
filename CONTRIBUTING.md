# Contributing

## Getting a build

JDK 21 or newer and Maven 3.9 or newer. Nothing else — no code generator, no native toolchain, no
container runtime.

```bash
mvn verify                     # compiles with -Werror, runs all 504 tests
./scripts/preflight.sh         # everything CI will run, in the same order
```

`preflight.sh` is the one to run before pushing. It includes the second JDK, which has already
caught a class of bug that no in-process check can see.

## Where a change goes

| Changing | Goes in | And needs |
| --- | --- | --- |
| A rule | `cairn-core` | the same change in `cairn-testkit`'s `ReferenceKernel`, and the differential test still green |
| The on-disk format | `cairn-codec` | a format version bump, a `CHANGELOG.md` migration note, and a new case in the rejection corpus |
| Durability | `cairn-store` | a crash test that damages a real file, not a mock |
| Delivery | `cairn-effects` | a statement of which crash window it changes |
| A check | `cairn-testkit` | the flaw it catches is still caught (`-Dtest=FlawTest`) |
| The API | `cairn-server` | an `ApiTest` case over a real socket, with the status code asserted |

## The rules that get a pull request turned down

**A rule change without the reference implementation.** `ReferenceKernel` is a second, independent
implementation of the same rules, and the differential test compares the two after every command.
Changing one and not the other makes the test red; changing both without thinking makes the test
useless. Say in the pull request why they still deserve to agree.

**A weakened check.** A check that stops catching its flaw is indistinguishable from a passing
build. `FlawTest` asserts the mapping between each known defect and the check that catches it, so
this fails loudly — please do not "fix" it by editing the mapping.

**A number in the README that was not re-measured.** Every figure there has the command that
produced it. If a change moves one, re-run the command and update both.

**A new rejection code with no way to reach it.** `SoakTest` asserts the sweep reaches every code in
`RejectionCode`. Add the code and the generator shape that produces it.

**An on-disk format change without a version bump.** A build that half-reads a file it does not
understand is worse than one that refuses to start.

## The rules that make a test acceptable

- **No sleeping to wait for progress.** The deterministic layers have no clock. Integration tests
  poll a condition with a deadline. A loaded runner should make a test slower, never flakier.
- **Anything randomized takes a seed and prints it on failure.** A red test that cannot be replayed
  is close to worthless.
- **A rejection test asserts the state did not change.** "It said no" and "it said no and changed
  nothing" are different claims.
- **A chaos test asserts the run was hostile.** If a fault might not fire on every seed, assert it
  across the soak sweep rather than per seed — requiring a rare event per seed is requiring a
  coincidence.
- **An assertion message names the state**, not just the expected value.

## Style

Whatever `-Xlint:all -Werror` and the surrounding code say. Two conventions that are not enforced by
the compiler and are enforced in review:

**Comments say why, not what.** The code already says what. A comment that survives is one that
records a decision, a cost, or a failure that motivated the line — ideally with the measurement or
the seed.

**Say what a thing does not cover.** In a javadoc, in a design note, in a pull request. The
`## What this does not cover` section of the pull request template is the most useful part of it; a
change that claims to cover everything is either very small or not being honest about its edges.

## Commits

Conventional commits, because the changelog is assembled from them:

```
feat(core): reject an ingest while a collection order is still owed
fix(store): buffer segment reads instead of two syscalls per record
docs(testing): say what the simulator does not model
test(testkit): prove the checker catches an aliased snapshot restore
```

One logical change per commit. A commit that both fixes a bug and reformats a file is a commit
nobody can review or revert.

## Reporting a bug a simulation found

The seed is the whole report. A run is a function of it, so

```
I5 Stage exclusivity violated at step 412 (seed 8123): ...
```

is reproducible with `-Dcairn.sim.seeds=` and that number, on any machine. Open an issue with the
seed and the message; a fix should add the seed to the list in `SimTest` so it is replayed forever
afterwards.

## Security

Do not open a public issue. `SECURITY.md` has the process.
