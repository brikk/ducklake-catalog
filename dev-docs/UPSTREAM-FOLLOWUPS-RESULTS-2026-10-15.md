# UF-01–UF-04 execution results — October 15

Work is sequential, using the IDs in the [existing TODO](TODO-UPSTREAM-FOLLOWUPS-2026-10-13.md).
Native failures below remain qualification failures, not fixed engine behavior.
No connector repositories, production/preview pins or 1.1 write guards are changed.

## UF-01 — transient CDC rows (#1675)

**Catalog parity verified; native gate fails; connector filtering handed off.**

`ducklake_cdc_transient.py` uses real INSERT→DELETE and UPDATE→DELETE transactions,
independent literal row IDs/images, all three native CDC functions, narrow and
combined windows, later deletes, inline file-delete tags and flush. It exits 1
for wrong results or SQL errors, while live-row controls are checked separately.

Observed on maintained Python 1.5.6 (core `069cc9f9b5`, DuckLake `ac7595b0`) and
locked preview (core `96063b9e39`, DuckLake `e8924cfc9c`), both explicit 1.0 and
fresh preview 1.1-dev1:

- Updating ids 0–2 then deleting id 0 within the transaction should emit one
  deletion `(rowid=0,id=0,v=0)`, plus two real update pairs. Native CDC instead
  emits four update images for row 0, including its uncommitted `v=1` version.
- Inserting ids 0–2 and deleting id 0 before COMMIT should emit only inserts 1,2;
  native CDC emits a phantom update pair for id 0 too.
- Later-snapshot delete and live-row controls pass. Flushing inline deletion tags
  does not repair the transient images. No failing expectation was replaced.

The JVM PostgreSQL candidate regression verifies both new-file postimage and
old-file preimage/deletion candidates remain available. A subsequent cumulative
delete file retains both embedded delete snapshots and is offered for the later
window. Dropping the whole new file would lose real updates; dropping the old file
would lose the real deletion. No catalog SQL rewrite is warranted by this case.

Reader handoff: suppress an individual version on BOTH insertion/deletion sides
when its per-row insert and deletion snapshots are equal, **before** update pairing.
Use embedded tags when present, never physical position as absolute row ID or
file-level begin snapshots for mixed-origin files. Retain later deletes; for inline
data, equal begin/end likewise means no committed version. Candidate DTOs and JSON
are unchanged; their documentation now states this obligation.

Verification: `gradlew check -Dducklake.corpus.dirs=all --no-configuration-cache`
passed (590 JUnit tests, zero failures/errors/skips; 9,697 replay records, existing
31 file/12 record skips). Detekt and Java 17 checkAbi passed. Seven native harness
unit tests passed; strict probes deliberately exited 1 on all three sampled targets.

Run a probe using an explicitly selected pinned package, e.g.:

```sh
<maintained>/bin/python dev-docs/probes/ducklake_cdc_transient.py --temp-root /tmp/opencode
<preview>/bin/python dev-docs/probes/ducklake_cdc_transient.py --temp-root /tmp/opencode --version 1.0
```

Disposable local fixtures are removed at probe exit. Re-run on the actual RC engine,
then obtain independent connector verification; a main-branch PR merge is not certification.
