# Releasing

## Before

```bash
./scripts/preflight.sh
```

That is the whole gate, and it includes the second JDK and a 500-seed soak. Then, by hand:

- [ ] `CHANGELOG.md` has a section for the version, and it includes what was **found** rather than
      only what was added
- [ ] Every number in `README.md` was re-measured with the command printed beside it
- [ ] `./scripts/demo.sh` still produces the transcript in the README
- [ ] If the on-disk format changed: the version was bumped, and `CHANGELOG.md` says what a reader
      of the old format should do
- [ ] `docs/design/0005-scope.md` still describes what is actually absent

## Cutting it

```bash
mvn -B versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
mvn -B -ntp verify

git commit -am "chore: 0.2.0"
git tag -a v0.2.0 -m "v0.2.0"
git push origin main v0.2.0
```

The tag pushes and `release.yml` takes over: it runs the whole suite again, **asserts the version in
the POM matches the tag** — otherwise the artifact attached to `v0.2.0` is `0.1.0`, which is the kind
of mistake only discovered by whoever downloads it — builds the release artifacts, and attaches the
runnable jar and the sources jars to a GitHub release.

## Publishing to Maven Central

Separate, manual, and after looking at what the release attached. `publish.yml` is triggered by
hand:

- with `dry-run: true` (the default) it builds and signs without uploading
- with `dry-run: false` it uploads to a staging repository

Uploading does **not** publish. `autoPublish` is off in the POM on purpose, because a published
version can never be replaced — so the last step is a human looking at the staged artifacts in the
Central portal and pressing release.

Credentials are in the `maven-central` GitHub environment; `SETUP-PUBLISHING.md` is what a maintainer
follows to supply them.

## After

- [ ] The jar on the release page starts: `java -jar cairn-server-0.2.0.jar --help`
- [ ] `docker compose up -d --wait` against the tag
- [ ] Set the next version to `0.3.0-SNAPSHOT` and push

## If a release is wrong

A GitHub release can be deleted and a tag can be moved. **A version published to Maven Central
cannot.** If a bad version reaches Central, the only remedy is to publish a higher one and mark the
bad one deprecated in its documentation. That asymmetry is why publishing is a separate, manual,
two-key operation and why `dry-run` defaults to true.
