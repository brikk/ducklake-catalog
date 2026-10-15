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

## UF-02 — CDC user-name collisions (#1723)

**JVM identity/value projection verified; native gate fails; no local rename applied.**

`ducklake_cdc_names.py` tests both file and inline paths with user columns
`rowid_1,rowid,snapshot_id_1,snapshot_id,v`. Two rows deliberately share user
identity values 0,0. After a normal committed UPDATE (no transient rows), the
independent expectation is four images, internal IDs 0,1 and the real snapshot.
Both maintained and locked-preview engines (explicit 1.0 and fresh 1.1-dev1)
instead emit **eight images** with apparent snapshot/row ID 0,0. `WHERE rowid=1`
incorrectly returns nothing. Insertions/deletions also fail the positional
metadata-plus-payload expectations. History and live-value controls pass.

The JVM regression reads native-created inline versions through the existing
decoded API. It verifies exactly four candidates, unchanged user names and field
IDs, original 0/1 row IDs on both images, correct begin/end snapshots and distinct
payload values. This API already carries identity separately from user values;
it does not perform the faulty name-based CDC join. No speculative alias changes
or public DTO/JSON changes were made.

Compatibility/handoff: native #1723 reserves the public CDC `snapshot_id,rowid`
outputs for metadata and suffixes colliding **payload** names, avoiding existing
suffixes. Future native `SELECT *`/name-based consumers must qualify the actual
output description; do not hardcode `rowid_1` for the user field if that name
already exists. Pair images using internal snapshot and absolute row identity,
never a user-named column. The JVM catalog retains original names and field IDs;
connector SQL aliases/output naming are its agents' responsibility.

Verification: full `check` with all corpus directories passed: **591 JUnit tests**,
zero failures/errors/skips; 9,697 corpus records, unchanged corpus skips, detekt
and Java 17 checkAbi. Strict native probes exit 1 on all three sampled targets.
Run `ducklake_cdc_names.py` with the same `--temp-root`/`--version` options as UF-01.

## UF-03 — Quack snapshot ceilings (#1756)

**Local timestamp lookup fixed; JVM pinned sessions pass all five routes;
native Quack ceiling gate still fails.**

The new five-route contract pins a read session, commits a competing real-Parquet
append through another catalog, then checks head, snapshot/change listings,
explicit future-ID lookup, timestamp lookup, file candidates/CDC and nested
sessions. New metadata is invisible until the session ends, including when callers
explicitly request the new ID. After release the new snapshot/files are visible.
This passes on PostgreSQL, MySQL, local DuckDB, native Quack and Java Quack with
one-connection pools. The explicit remote BEGIN in our Quack metadata adapter
already prevents the native DuckLake metadata-session leak; no artificial head
filter or new read-session semantics were added.

These checks reproduced a **local timestamp-lookup defect** on local DuckDB and
direct Java Quack: jOOQ 3.19 renders `TIMESTAMP(6) WITH TIME ZONE`, which DuckDB
rejects. The DuckDB dialect now binds UTC ISO instant text and explicitly casts to
`TIMESTAMPTZ`; PostgreSQL/MySQL retain their rendering. Instant formatting retains
seconds at exact minutes. Cases verify a current lookup, exact snapshot timestamp,
pre-history timestamp, pinned ceiling and visibility after release. No public API,
time ordering/tie-break semantics, DTOs or dependency pins changed.

`ducklake_quack_snapshot_ceiling.py` uses matched native peers in separate processes,
two native clients, explicit BEGIN, independent live values and snapshots, future
version rejection, timestamp time travel, timestamp/version CDC and post-rollback
controls. Server startup/lifetime and teardown are bounded, including terminate/
kill fallback. Only the specific no-such-visible-snapshot error satisfies a rejection
check; a transport error does not. The harness has eight passing unit tests.

Maintained peers (core `069cc9f9b5`, Quack `7e80f7f`, DuckLake `ac7595b0`) and
locked-preview peers (core `96063b9e39`, Quack `974927a394`, DuckLake `e8924cfc9c`),
preview with explicit 1.0 and fresh 1.1-dev1, all fail the strict native gate:
the live table remains pinned at two rows, but snapshot listing reveals head 3,
timestamp time travel/CDC returns three, and explicit future version/CDC requests
succeed instead of being rejected. Repin/unpinned controls pass. These are not
fixed by the JVM cast correction; qualify upstream #1756 on actual shipping peers.

Verification: forced full `check --rerun-tasks` with all corpus directories passed:
**596 JUnit tests**, zero failures/errors/skips; **9,697 corpus records**, unchanged
corpus skips, detekt and Java 17 checkAbi. Strict native gates exited 1 on all three
targets. Run `ducklake_quack_snapshot_ceiling.py` with the standard probe options.
