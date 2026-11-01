# Catalog nested-type signatures

N03 development contract for `0.13.0-SNAPSHOT`. This is the catalog API's
`DucklakeColumn.columnType` projection from `getTableColumns`, **not** SQL, a JDBC
driver format, physical DuckLake metadata or a new payload-type admission policy.
Consumer adoption/verification is required before deploying the correction.

## Grammar and names

```text
type   := atom | decimal(p,s) | list<type> | map<type,type> | struct<field:type,...>
field  := ASCII identifier | double-quoted value with doubled embedded quotes
```

ASCII identifiers match `[A-Za-z_][A-Za-z0-9_]*`. Their existing output is unchanged,
including case. All other field names are double-quoted using the existing
`DucklakeQuotedList` encoding. Backslash is literal, not an escape character.
Whitespace inside quoted names and every original character are preserved.

Examples:

```text
struct<a:int32,b:decimal(18,2),MiXeD:varchar>
struct<"a,b":int32,"x:y":varchar,"q""uote":int32," spaced ":varchar,"雪":int32>
list<struct<"a,b":int32>>
map<varchar,struct<"x:y":int32>>
```

The shared Java/Kotlin entry point is:

```java
InlinedTypeNode type = DucklakeTypeSignature.parse(column.getColumnType());
```

It canonicalizes type keywords without lowercasing field names. The root name is
empty; LIST children are named `element`, MAP children `key` and `value`, and
STRUCT children retain their names and order. Decimal parameter commas are not
field separators. Malformed syntax/arity, depth over64, more than4,096 nodes and
input over16,384 characters fail with `IllegalArgumentException`, never truncation.
Parsing a type tag does not qualify its storage/value conversion or engine support.

`getAllColumnsWithParentage` remains unchanged: it returns stored type tags and
separate stable-ID parent/child rows. Consumers that already construct types from
that complete tree can continue doing so; do not parse a bare `struct` tag as if
it contained its children. Snapshot visibility, field IDs, values, existing DTO
constructors/JSON shapes, write guards and format opt-in do not change.

## Consumer cutover

The old Trino/Doris string parsers are not quote-aware, and both lowercase their
entire input. They must use the shared parser or the existing complete parent-aware
tree before adopting the corrected catalog artifact. Do not just split on comma/
colon, strip quotes, or lowercase the complete signature. Existing simple types
remain supported; unsupported engine types still fail explicitly.

No blanket compatibility shim is required for an unclaimed external grammar.
Record the exact catalog commit/artifact and plugin changes/tests for both owned
consumers. The library does not automatically publish, update dependencies, enable
1.1 reads/writes or claim connector qualification from its own tests. See the
[consumer policy](../dev-docs/DUCKLAKE-1.1-CONSUMER-CONTRACT.md).
