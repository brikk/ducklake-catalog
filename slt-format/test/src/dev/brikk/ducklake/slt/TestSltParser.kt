package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestSltParser {

    @Test
    fun `parses statement ok and error with expectation`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement ok
                CREATE TABLE t(i INTEGER);

                statement error
                INSERT INTO nope VALUES (1);
                ----
                does not exist
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(2)
        val ok = file.records[0] as SltStatement
        assertThat(ok.expectError).isFalse()
        assertThat(ok.sql).contains("CREATE TABLE")
        val err = file.records[1] as SltStatement
        assertThat(err.expectError).isTrue()
        assertThat(err.expectedError).isEqualTo("does not exist")
    }

    @Test
    fun `parses query with types sort mode label and expected block`() {
        val file =
            SltParser.parse(
                "t",
                """
                query II rowsort lbl
                SELECT i, j FROM t
                ----
                1	2
                3	4
                """.trimIndent(),
            )
        val q = file.records.single() as SltQuery
        assertThat(q.types).isEqualTo("II")
        assertThat(q.sortMode).isEqualTo(SortMode.ROWSORT)
        assertThat(q.connection).isNull()
        assertThat(q.label).isEqualTo("lbl")
        assertThat(q.expected).containsExactly("1\t2", "3\t4")
        assertThat(q.goldenFile).isNull()
    }

    @Test
    fun `query modifiers are positional as upstream`() {
        fun query(header: String) =
            SltParser.parse("t", "$header\nSELECT 1\n----\n1\n").records.single() as SltQuery
        // second token: sort style if it parses as one, else the connection name
        query("query I con1").let {
            assertThat(it.sortMode).isEqualTo(SortMode.NOSORT)
            assertThat(it.connection).isEqualTo("con1")
            assertThat(it.label).isNull()
        }
        query("query I nosort alltypes").let {
            assertThat(it.sortMode).isEqualTo(SortMode.NOSORT)
            assertThat(it.connection).isNull()
            assertThat(it.label).isEqualTo("alltypes")
        }
        // upstream aliases: none/sort
        assertThat(query("query I sort").sortMode).isEqualTo(SortMode.ROWSORT)
        assertThat(query("query I none").sortMode).isEqualTo(SortMode.NOSORT)
        assertThat(query("query I valuesort").sortMode).isEqualTo(SortMode.VALUESORT)
        // third token is ALWAYS the label — even when the second was a connection and the third
        // spells a sort style (upstream does not reorder)
        query("query I con1 rowsort").let {
            assertThat(it.connection).isEqualTo("con1")
            assertThat(it.sortMode).isEqualTo(SortMode.NOSORT)
            assertThat(it.label).isEqualTo("rowsort")
        }
        // a made-up name in second position is a connection (not a label)
        query("query I mylabel").let {
            assertThat(it.connection).isEqualTo("mylabel")
            assertThat(it.label).isNull()
        }
    }

    @Test
    fun `query type string must be non-empty TIR`() {
        val file =
            SltParser.parse(
                "t",
                """
                query
                SELECT 1
                ----
                1

                query IX
                SELECT 1
                ----
                1

                query TIR
                SELECT 1
                ----
                1
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(3)
        assertThat((file.records[0] as SltUnsupported).directive).isEqualTo("query")
        assertThat((file.records[1] as SltUnsupported).directive).isEqualTo("query")
        assertThat((file.records[2] as SltQuery).types).isEqualTo("TIR")
    }

    @Test
    fun `parses empty expected block as empty result`() {
        val file =
            SltParser.parse(
                "t",
                """
                query I
                SELECT i FROM t WHERE false
                ----

                statement ok
                SELECT 1
                """.trimIndent(),
            )
        val q = file.records[0] as SltQuery
        assertThat(q.expected).isEmpty()
        assertThat(file.records[1]).isInstanceOf(SltStatement::class.java)
    }

    @Test
    fun `parses loop and foreach with bodies`() {
        val file =
            SltParser.parse(
                "t",
                """
                loop i 0 3

                statement ok
                SELECT ${'$'}{i}

                endloop

                foreach v a b

                statement ok
                SELECT '${'$'}{v}'

                endloop
                """.trimIndent(),
            )
        val loop = file.records[0] as SltLoop
        assertThat(loop.values).containsExactly("0", "1", "2")
        assertThat(loop.body).hasSize(1)
        val foreach = file.records[1] as SltLoop
        assertThat(foreach.values).containsExactly("a", "b")
    }

    @Test
    fun `directives require and test-env`() {
        val file =
            SltParser.parse(
                "t",
                """
                require ducklake

                test-env DUCKLAKE_CONNECTION {TEST_DIR}/{UUID}.db
                """.trimIndent(),
            )
        assertThat((file.records[0] as SltRequire).requirement).isEqualTo("ducklake")
        val env = file.records[1] as SltTestEnv
        assertThat(env.name).isEqualTo("DUCKLAKE_CONNECTION")
        assertThat(env.value).isEqualTo("{TEST_DIR}/{UUID}.db")
    }

    @Test
    fun `unknown constructs surface as unsupported`() {
        val file =
            SltParser.parse(
                "t",
                """
                concurrentloop i 0 10

                statement ok
                SELECT 1

                endloop
                """.trimIndent(),
            )
        assertThat(file.records.filterIsInstance<SltUnsupported>()).isNotEmpty()
    }

    @Test
    fun `skipif guards the next record`() {
        val file =
            SltParser.parse(
                "t",
                """
                skipif duckdb
                query I
                SELECT 1
                ----
                1
                """.trimIndent(),
            )
        val cond = file.records.single() as SltConditional
        assertThat(cond.skipIf).isTrue()
        assertThat(cond.engine).isEqualTo("duckdb")
        assertThat(cond.record).isInstanceOf(SltQuery::class.java)
    }
}

class TestSltParserLoopEdges {

    @Test
    fun `concurrentloop consumes its body and the rest of the file still parses`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement ok
                CREATE TABLE t(i INTEGER);

                concurrentloop i 0 4

                statement ok
                INSERT INTO t VALUES (${'$'}{i});

                endloop

                query I
                SELECT count(*) FROM t
                ----
                4
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(3)
        assertThat(file.records[1]).isInstanceOf(SltUnsupported::class.java)
        assertThat((file.records[1] as SltUnsupported).directive).isEqualTo("concurrentloop")
        val tail = file.records[2] as SltQuery
        assertThat(tail.sql).contains("count(*)")
        assertThat(tail.expected).containsExactly("4")
    }

    @Test
    fun `stray endloop is reported and does not truncate`() {
        val file =
            SltParser.parse(
                "t",
                """
                endloop

                statement ok
                SELECT 1
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(2)
        assertThat((file.records[0] as SltUnsupported).directive).isEqualTo("endloop")
        assertThat(file.records[1]).isInstanceOf(SltStatement::class.java)
    }

    @Test
    fun `skipif before a loop guards the whole loop`() {
        val file =
            SltParser.parse(
                "t",
                """
                skipif duckdb
                loop i 0 2

                statement ok
                SELECT ${'$'}{i}

                endloop

                statement ok
                SELECT 'after'
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(2)
        val guard = file.records[0] as SltConditional
        assertThat(guard.record).isInstanceOf(SltLoop::class.java)
        assertThat((guard.record as SltLoop).body).hasSize(1)
        assertThat((file.records[1] as SltStatement).sql).contains("after")
    }

    @Test
    fun `statement maybe keeps its expected message`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement maybe
                SELECT 1
                ----
                some error
                """.trimIndent(),
            )
        val st = file.records.single() as SltStatement
        assertThat(st.mayError).isTrue()
        assertThat(st.expectError).isTrue()
        assertThat(st.expectedError).isEqualTo("some error")
    }
}

class TestSltParserModeSkip {

    @Test
    fun `mode skip drops records until mode unskip and keeps the rest`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement ok
                SELECT 'before'

                # FIXME: this does not work correctly
                mode skip

                query IIII
                FROM ducklake.table_changes('test', 3, 5) ORDER BY ALL
                ----
                3	1	insert	1
                4	2	insert	2

                statement error
                SELECT 'also skipped'
                ----
                x

                mode unskip

                query III
                SELECT 'after'
                ----
                0	2	0
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(2)
        assertThat((file.records[0] as SltStatement).sql).isEqualTo("SELECT 'before'")
        assertThat((file.records[1] as SltQuery).sql).isEqualTo("SELECT 'after'")
        assertThat(file.records.filterIsInstance<SltUnsupported>()).isEmpty()
    }

    @Test
    fun `mode skip without unskip drops the rest of the file including require and loops`() {
        val file =
            SltParser.parse(
                "t",
                """
                # This test is currently failing
                mode skip

                require ducklake

                test-env DUCKLAKE_CONNECTION __TEST_DIR__/{UUID}.db

                statement ok

                loop i 0 3

                statement ok
                SELECT ${'$'}{i}

                endloop

                statement ok
                SELECT 1
                """.trimIndent(),
            )
        assertThat(file.records).isEmpty()
    }

    @Test
    fun `mode skip nests like the upstream counter`() {
        val file =
            SltParser.parse(
                "t",
                """
                mode skip

                mode skip

                statement ok
                SELECT 'skipped'

                mode unskip

                statement ok
                SELECT 'still skipped'

                mode unskip

                statement ok
                SELECT 'kept'
                """.trimIndent(),
            )
        assertThat(file.records.map { (it as SltStatement).sql }).containsExactly("SELECT 'kept'")
    }

    @Test
    fun `mode skip inside a loop body drops only the region and keeps loop structure`() {
        val file =
            SltParser.parse(
                "t",
                """
                loop i 0 2

                statement ok
                SELECT 'a${'$'}{i}'

                mode skip

                statement ok
                SELECT 'skipped'

                mode unskip

                statement ok
                SELECT 'b${'$'}{i}'

                endloop

                statement ok
                SELECT 'after'
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(2)
        val loop = file.records[0] as SltLoop
        assertThat(loop.body.map { (it as SltStatement).sql }).containsExactly("SELECT 'a\${i}'", "SELECT 'b\${i}'")
        assertThat((file.records[1] as SltStatement).sql).isEqualTo("SELECT 'after'")
    }

    @Test
    fun `other modes an unbalanced unskip and a guarded mode are unsupported`() {
        val file =
            SltParser.parse(
                "t",
                """
                mode output_hash

                mode unskip

                onlyif duckdb
                mode skip

                statement ok
                SELECT 1
                """.trimIndent(),
            )
        val unsupported = file.records.filterIsInstance<SltUnsupported>()
        assertThat(unsupported.map { it.directive }).containsExactly("mode", "mode", "onlyif")
        assertThat(file.records.last()).isInstanceOf(SltStatement::class.java)
    }
}

class TestSltParserLineHandling {

    @Test
    fun `comment lines and separators count only at column 0 and SQL lines are verbatim`() {
        val file =
            SltParser.parse(
                "t",
                "statement ok\n" +
                    "SELECT 1, -- # not a comment\n" +
                    "  '----' AS s, -- not a separator either\n" +
                    "\t2\n" +
                    "\n" +
                    "query I\n" +
                    "SELECT '#x'\n" +
                    "----\n" +
                    "#x\n" +
                    " 1 \n",
            )
        val st = file.records[0] as SltStatement
        assertThat(st.sql).isEqualTo("SELECT 1, -- # not a comment\n  '----' AS s, -- not a separator either\n\t2")
        val q = file.records[1] as SltQuery
        // result rows are verbatim: a `#` row is a value, whitespace is kept
        assertThat(q.expected).containsExactly("#x", " 1 ")
    }

    @Test
    fun `a comment line terminates the SQL block`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement ok
                SELECT 1
                # trailing comment ends the statement
                statement ok
                SELECT 2
                """.trimIndent(),
            )
        assertThat(file.records.map { (it as SltStatement).sql }).containsExactly("SELECT 1", "SELECT 2")
    }

    @Test
    fun `expected error lines are verbatim and joined`() {
        val file =
            SltParser.parse(
                "t",
                "statement error\nSELECT 1\n----\n  Binder Error: x\n  hint\n",
            )
        assertThat((file.records.single() as SltStatement).expectedError).isEqualTo("  Binder Error: x\n  hint")
    }

    @Test
    fun `statements upstream rejects are unsupported not silently accepted`() {
        val file =
            SltParser.parse(
                "t",
                """
                statement error
                SELECT 'no ---- block'

                statement ok
                SELECT 'ok with block'
                ----
                unexpected

                statement ok

                statement maybe con1
                SELECT 'fine'
                ----
                """.trimIndent(),
            )
        assertThat(file.records).hasSize(4)
        assertThat((file.records[0] as SltUnsupported).directive).isEqualTo("statement error")
        assertThat((file.records[1] as SltUnsupported).directive).isEqualTo("statement ok")
        assertThat((file.records[2] as SltUnsupported).raw).contains("empty statement")
        val ok = file.records[3] as SltStatement
        assertThat(ok.mayError).isTrue()
        assertThat(ok.connection).isEqualTo("con1")
        assertThat(ok.expectedError).isNull()
    }

    @Test
    fun `statement connection is the second token`() {
        val file = SltParser.parse("t", "statement ok con2\nSELECT 1\n")
        assertThat((file.records.single() as SltStatement).connection).isEqualTo("con2")
    }

    @Test
    fun `golden FILE result reference is modelled`() {
        val file =
            SltParser.parse(
                "t",
                """
                loop i 1 3

                query I
                PRAGMA tpch(${'$'}{i})
                ----
                <FILE>:duckdb/extension/tpch/dbgen/answers/sf1/q0${'$'}{i}.csv

                endloop

                query I
                SELECT 1
                ----
                <FILE>:a.csv
                extra row
                """.trimIndent(),
            )
        val q = (file.records[0] as SltLoop).body.single() as SltQuery
        assertThat(q.goldenFile).isEqualTo(SltGoldenFile("duckdb/extension/tpch/dbgen/answers/sf1/q0\${i}.csv"))
        assertThat(q.expected).containsExactly("<FILE>:duckdb/extension/tpch/dbgen/answers/sf1/q0\${i}.csv")
        // only a SINGLE <FILE>: row is a golden reference (upstream `values.size() == 1`)
        assertThat((file.records[1] as SltQuery).goldenFile).isNull()
    }
}

class TestSltParserForeach {

    private fun values(header: String): List<String> =
        (SltParser.parse("t", "$header\n\nstatement ok\nSELECT 1\n\nendloop\n").records.single() as SltLoop).values

    @Test
    fun `special tokens expand to the upstream lists in upstream order`() {
        assertThat(values("foreach t <signed>"))
            .containsExactly("tinyint", "smallint", "integer", "bigint", "hugeint")
        assertThat(values("foreach t <unsigned>"))
            .containsExactly("utinyint", "usmallint", "uinteger", "ubigint", "uhugeint")
        assertThat(values("foreach t <integral>")).isEqualTo(SltForeachTokens.SIGNED + SltForeachTokens.UNSIGNED)
        assertThat(values("foreach t <numeric>"))
            .isEqualTo(SltForeachTokens.SIGNED + SltForeachTokens.UNSIGNED + listOf("float", "double"))
        assertThat(values("foreach t <alltypes>")).isEqualTo(
            SltForeachTokens.SIGNED + SltForeachTokens.UNSIGNED + listOf("float", "double", "bool", "interval", "varchar"),
        )
        assertThat(values("foreach c <compression>")).containsExactly(
            "none", "uncompressed", "rle", "bitpacking", "dictionary", "fsst", "dict_fsst", "alp", "alprd",
        )
        assertThat(values("foreach c <all_types_columns>")).hasSize(53).startsWith("bool", "tinyint").endsWith(
            "fixed_array_of_int_list",
            "list_of_fixed_int_array",
        )
        // matched case-insensitively, as upstream lower-cases the token
        assertThat(values("foreach t <SIGNED>")).isEqualTo(SltForeachTokens.SIGNED)
    }

    @Test
    fun `special tokens mix with literals and exclusions`() {
        assertThat(values("foreach t <numeric> !float !double varchar"))
            .isEqualTo(SltForeachTokens.SIGNED + SltForeachTokens.UNSIGNED + "varchar")
        // !tok that matches nothing collected so far is kept verbatim (upstream behaviour)
        assertThat(values("foreach t a !b")).containsExactly("a", "!b")
        // exclusion is literal and only sees earlier tokens
        assertThat(values("foreach t !a a")).containsExactly("!a", "a")
    }

    @Test
    fun `comma iterators are kept raw and must have matching arity`() {
        assertThat(values("foreach a,b 1,x 2,y")).containsExactly("1,x", "2,y")
        val bad = SltParser.parse("t", "foreach a,b 1,x 2\n\nstatement ok\nSELECT 1\n\nendloop\n")
        assertThat((bad.records.first() as SltUnsupported).directive).isEqualTo("foreach")
    }

    @Test
    fun `malformed loops are unsupported`() {
        assertThat((SltParser.parse("t", "foreach t\n").records.single() as SltUnsupported).directive).isEqualTo("foreach")
        assertThat((SltParser.parse("t", "loop i 0\n").records.single() as SltUnsupported).directive).isEqualTo("loop")
        assertThat((SltParser.parse("t", "loop i 0 x\n").records.single() as SltUnsupported).directive).isEqualTo("loop")
        // an empty range is a valid zero-iteration loop
        assertThat(values("loop i 3 3")).isEmpty()
    }
}

