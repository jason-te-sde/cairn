# Setting up publishing

What a maintainer has to supply before `publish.yml` can do anything. None of it can live in the
repository, which is why this is a document rather than a script.

## 1. A Central Portal account

Maven Central is [central.sonatype.com](https://central.sonatype.com) now, not OSSRH.

1. Sign in with GitHub.
2. Register the namespace `io.github.jason-te-sde`. Because it is a `io.github.*` namespace, the
   verification is proving you control that GitHub account — the portal asks you to create a public
   repository with a generated name. No DNS involved.
3. Generate a **user token**. That gives a username and a password, which are *not* your portal
   login.

## 2. A signing key

Central requires a detached GPG signature for every artifact.

```bash
gpg --full-generate-key           # RSA 4096, no expiry, the project's email
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --armor --export-secret-keys <KEY_ID> > private.asc
```

The public key has to be on a keyserver that Central checks, or the upload is rejected with a
message about a missing key that does not mention keyservers.

Keep `private.asc` out of the repository, and delete it from disk once it is in GitHub.

## 3. GitHub secrets

In an environment called `maven-central` — an environment rather than repository secrets, so the job
that uses them can require a review before it runs:

| Secret | What |
| --- | --- |
| `CENTRAL_USERNAME` | the user token's username, not the portal login |
| `CENTRAL_PASSWORD` | the user token's password |
| `GPG_PRIVATE_KEY` | the whole contents of `private.asc`, including the header and footer lines |
| `GPG_PASSPHRASE` | the key's passphrase |

## 4. Check it without publishing anything

```bash
gh workflow run publish.yml -f dry-run=true
```

That builds, signs and verifies without uploading. Do this first. The failure modes are all in the
signing step and they are all easier to read before an upload is involved.

## Why the POM looks the way it does

Three details that are easy to get wrong and hard to diagnose:

**`--pinentry-mode loopback`.** Without it, signing on a machine with no terminal does not fail — it
*hangs*, waiting for a pinentry dialog nobody will ever see. The job times out an hour later with no
useful message.

**`release` and `publish` are separate profiles.** So building the release artifacts locally does not
require a GPG key, and so a mistake in one cannot silently skip signing in the other.

**`autoPublish` is false.** Uploading stages; a human releases. A published version can never be
replaced, so the last step is somebody looking at it. `RELEASING.md` says what to look at.

## What is currently missing

The account, the namespace and the key. The build path is written and exercised by the dry run; the
credentials behind it are not something a repository can contain, which is why the README says
**not on Maven Central yet** rather than claiming otherwise.
