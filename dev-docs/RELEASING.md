# Release process

## Verify before publishing

Verify the exact intended commit, versions, coordinates and compatibility notes
before publication. Keep all published modules/test fixtures on the same version;
the release tag must match it. Passing publication is not proof CI passed: the
workflows currently run independently (review B02/B03 in the [backlog](BACKLOG.md)).

The [build guide](CONTRIBUTING.md) covers full checks. Review consumer-facing
changes in [docs](../docs/README.md), especially retry/cleanup and migration contracts.

## Publishing

- **Snapshot:** a push to `main` at `*-SNAPSHOT` triggers
  [publish-snapshot.yml](../.github/workflows/publish-snapshot.yml).
- **Release:** set root/module versions to a non-SNAPSHOT value, commit the release,
  and push a matching `v<version>` tag. [publish-release.yml](../.github/workflows/publish-release.yml)
  uploads an **AUTOMATIC** Central Portal deployment. The portal validates/releases
  it without a manual Publish click; the workflow does not wait for that result.
  Then advance `main` to the next `-SNAPSHOT`.
- **Stuck deployment:** [publish-pending-deployment.yml](../.github/workflows/publish-pending-deployment.yml)
  supports explicit `status`, `publish` or `drop` by deployment ID. Inspect the
  actual deployment before authorizing a destructive action.

Workflows read the Central Portal token and PGP key from organization
`KOTLIN_TOOLCHAIN_*` secrets, mapped to vanniktech `ORG_GRADLE_PROJECT_*` properties.
Do not log credentials or put them in documentation/URLs.

## Confirm the outcome

Check CI and publication independently. Verify public Maven metadata and downloadable
POM/JAR/Gradle metadata for all artifacts, including catalog test fixtures. Record
the exact snapshot coordinate or stable version and commit SHA. Do not announce a
release merely because an upload completed. Commit permission is not push/tag/
publication permission; require the user's explicit authorization.
