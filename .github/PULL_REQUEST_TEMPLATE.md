## What this changes

<!-- One or two sentences. The commit message is where the detail goes. -->

## Why

<!-- The problem, not the patch. If it fixes a bug a simulation found, the seed belongs here. -->

## Checklist

- [ ] `./scripts/preflight.sh` passes, including the second JDK
- [ ] New behaviour has a test in the cheapest layer that can catch its bugs
      (see `docs/testing.md`)
- [ ] If a rule changed: the reference implementation in `cairn-testkit` changed with it, and the
      differential test still passes
- [ ] If a check changed: the flaw it is supposed to catch is still caught
      (`mvn test -pl cairn-testkit -am -Dtest=FlawTest`)
- [ ] If the on-disk format changed: the format version was bumped and `CHANGELOG.md` says what a
      reader of the old format should do
- [ ] If a number in the README changed: it was re-measured, and the command that produced it is
      still the command in the README

## What this does not cover

<!-- The section that makes a review useful. A pull request that claims to cover everything is
     either very small or not being honest about its edges. -->
