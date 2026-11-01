# SLT resource limits

**Implementation in place; strict project qualification is pending.**

The SLT parser, pure expander and replay driver use fixed **per-file** ceilings.
They are safety budgets, not DuckDB dialect limits. Existing `parse(path, content)`
and `expand(file, engine, env)` JVM descriptors and the public AST constructors
are preserved; hand-built ASTs cannot bypass validation.

| Budget | Ceiling |
|---|---:|
| Source UTF-16 characters, aggregate AST text, or initial environment text | 1,048,576 each |
| Source lines / aggregate AST text items | 50,000 each |
| Static records | 20,000 |
| Combined loop/conditional nesting | 32 |
| Values per loop, comma parts, or foreach peak output | 10,000 each |
| Aggregate retained loop values | 20,000 |
| Expanded traversal visits, including empty loop iterations | 100,000 |
| Individual expanded text | 1,048,576 characters |
| Cumulative expanded/output characters, including intermediate replacements | 16,777,216 |
| Cumulative substitution/condition scanning | 67,108,864 characters |

Traversal costs conservatively include guarded bodies and validate the structure
of zero-iteration bodies. Source directives are tokenized with a bounded sequence;
range cardinalities (including overflowing Long spans) and foreach collection
insertions are checked before materializing values. No nested product is eagerly
unrolled to discover its size. Comma splits are bounded too.

## Refusal is explicit, not truncation

- `SltParser.parse` returns **one** `SltUnsupported` with directive `resource-limit`,
  line and diagnostic. It drops the entire parsed prefix so it is not runnable.
  Unknown/malformed dialect constructs keep their existing unsupported behavior.
- `SltLimits.validate` and `SltExpander.expand` throw `SltLimitExceeded` on a resource
  violation, including a parser-emitted resource-limit marker. The expander never
  silently drops that marker or returns a partially expanded list. Ordinary
  unsupported constructs retain the documented omission behavior.
- Replay reports an explicit `RESOURCE LIMIT:` file halt. Static violations refuse
  before oracle creation or effects; dynamic substitution exhaustion preserves every
  already-completed pass, failure and skip. It never fabricates a SQL result or
  converts an exception into a value.
- `CorpusRunner` reads through a bounded character buffer. Reading and parsing both
  happen **inside** the existing per-file watchdog, before replay. Disabling the
  watchdog does not disable allocation/expansion ceilings. Timeout still freezes
  progress and quarantines shared engine/backend callbacks; an unresponsive worker
  cannot be forcibly stopped or reused. A normal resource refusal does not prevent
  a later independent file from running.

These controls do **not** sandbox trusted SQL, bound arbitrary JDBC result sets,
constrain native SQL memory or revoke SQL filesystem/network capabilities. Choose
an appropriate process/container memory limit for the corpus; do not mistake a
watchdog interrupt for hard process isolation.

The pristine 948-file corpus census (including already-curated exclusions) has
zero resource refusals: maximum22,382 source characters,785 lines,156 modeled
records, depth3,512 values per loop and1,446 expanded traversal visits. No corpus
exclusion or golden is added to fit these budgets.
