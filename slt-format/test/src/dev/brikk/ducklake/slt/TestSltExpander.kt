package dev.brikk.ducklake.slt

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestSltExpander {

    private fun expand(content: String, engine: String = "duckdb", env: Map<String, String> = emptyMap()) =
        SltExpander.expand(SltParser.parse("t", content), engine, env)

    @Test
    fun `statement ok error and query map to kinds`() {
        val records = expand(
            """
            statement ok
            CREATE TABLE t(i INTEGER);

            statement error
            INSERT INTO nope VALUES (1);
            ----
            does not exist

            query I
            SELECT i FROM t
            ----
            1
            """.trimIndent(),
        )
        assertThat(records.map { it.kind }).containsExactly(RecordKind.OK, RecordKind.ERROR, RecordKind.QUERY)
        assertThat(records[0].sql).contains("CREATE TABLE")
        assertThat(records[0].expectedError).isNull()
        assertThat(records[1].expectedError).isEqualTo("does not exist")
        assertThat(records[2].expectedError).isNull()
    }

    @Test
    fun `statement maybe is an error kind flagged mayError with an empty expectation as null`() {
        val records = expand(
            """
            statement maybe
            SET threads=4;
            ----

            statement maybe
            SET threads=4;
            ----
            some message
            """.trimIndent(),
        )
        assertThat(records).hasSize(2)
        assertThat(records[0].kind).isEqualTo(RecordKind.ERROR)
        assertThat(records[0].mayError).isTrue()
        assertThat(records[0].expectedError).isNull()
        assertThat(records[1].mayError).isTrue()
        assertThat(records[1].expectedError).isEqualTo("some message")
    }

    @Test
    fun `statement error is not mayError`() {
        val records = expand(
            """
            statement error
            SELECT 1;
            ----
            boom
            """.trimIndent(),
        )
        assertThat(records.single().mayError).isFalse()
    }

    @Test
    fun `loop unrolls body with numeric bindings substituted`() {
        val records = expand(
            """
            loop i 0 3

            statement ok
            INSERT INTO t VALUES (${'$'}{i});

            endloop
            """.trimIndent(),
        )
        assertThat(records).hasSize(3)
        assertThat(records.map { it.sql }).containsExactly(
            "INSERT INTO t VALUES (0);",
            "INSERT INTO t VALUES (1);",
            "INSERT INTO t VALUES (2);",
        )
    }

    @Test
    fun `foreach unrolls over explicit values`() {
        val records = expand(
            """
            foreach tbl a b

            statement ok
            DROP TABLE IF EXISTS ${'$'}{tbl};

            endloop
            """.trimIndent(),
        )
        assertThat(records.map { it.sql }).containsExactly(
            "DROP TABLE IF EXISTS a;",
            "DROP TABLE IF EXISTS b;",
        )
    }

    @Test
    fun `skipif for the target engine drops the record`() {
        val records = expand(
            """
            skipif duckdb
            statement ok
            SELECT 1;
            """.trimIndent(),
            engine = "duckdb",
        )
        assertThat(records).isEmpty()
    }

    @Test
    fun `skipif for a different engine keeps the record`() {
        val records = expand(
            """
            skipif postgres
            statement ok
            SELECT 1;
            """.trimIndent(),
            engine = "duckdb",
        )
        assertThat(records).hasSize(1)
        assertThat(records.single().sql).isEqualTo("SELECT 1;")
    }

    @Test
    fun `onlyif keeps the record only for the matching engine`() {
        val onlyDuck = """
            onlyif duckdb
            statement ok
            SELECT 1;
        """.trimIndent()
        assertThat(expand(onlyDuck, engine = "duckdb")).hasSize(1)
        assertThat(expand(onlyDuck, engine = "postgres")).isEmpty()
    }

    @Test
    fun `skipif var=value resolves against loop bindings`() {
        val records = expand(
            """
            foreach mode fast slow

            skipif mode=slow
            statement ok
            SELECT '${'$'}{mode}';

            endloop
            """.trimIndent(),
        )
        // slow iteration is skipped; only fast survives.
        assertThat(records.map { it.sql }).containsExactly("SELECT 'fast';")
    }

    @Test
    fun `env and test-env template vars are substituted`() {
        val records = expand(
            """
            test-env LAKE ${'$'}{__TEST_DIR__}/lake.db

            statement ok
            ATTACH 'ducklake:${'$'}{LAKE}' AS lk;
            """.trimIndent(),
            env = mapOf("__TEST_DIR__" to "/tmp/x"),
        )
        // test-env is not itself emitted; its value flows into the later statement.
        assertThat(records.single().sql).isEqualTo("ATTACH 'ducklake:/tmp/x/lake.db' AS lk;")
    }

    @Test
    fun `delimited bare key and brace forms both substitute`() {
        val records = expand(
            """
            statement ok
            COPY t TO '__TEST_DIR__/out' (FORMAT PARQUET); -- {FMT} ${'$'}{FMT}
            """.trimIndent(),
            env = mapOf("__TEST_DIR__" to "/d", "FMT" to "parquet"),
        )
        assertThat(records.single().sql)
            .isEqualTo("COPY t TO '/d/out' (FORMAT PARQUET); -- parquet parquet")
    }

    @Test
    fun `unknown template vars are left verbatim`() {
        val records = expand(
            """
            statement ok
            ATTACH 'ducklake:${'$'}{UUID}.db' AS lk;
            """.trimIndent(),
        )
        assertThat(records.single().sql).isEqualTo("ATTACH 'ducklake:\${UUID}.db' AS lk;")
    }

    @Test
    fun `env is substituted before loop bindings so env wins on a name clash`() {
        val records = expand(
            """
            foreach i loopval

            statement ok
            SELECT '${'$'}{i}', '{i}'

            endloop
            """.trimIndent(),
            env = mapOf("i" to "envval"),
        )
        assertThat(records.single().sql).isEqualTo("SELECT 'envval', 'envval'")
    }

    @Test
    fun `loop values are env-substituted before insertion`() {
        val records = expand(
            """
            foreach p {ROOT}/a __TEST_DIR__/b

            statement ok
            SELECT '${'$'}{p}'

            endloop
            """.trimIndent(),
            env = mapOf("ROOT" to "/r", "__TEST_DIR__" to "/t"),
        )
        assertThat(records.map { it.sql }).containsExactly("SELECT '/r/a'", "SELECT '/t/b'")
    }

    @Test
    fun `expected error is env-substituted but never loop-substituted`() {
        val records = expand(
            """
            loop i 0 1

            statement error
            SELECT ${'$'}{i}
            ----
            path {DATA_PATH}/x iteration ${'$'}{i}

            endloop
            """.trimIndent(),
            env = mapOf("DATA_PATH" to "/d"),
        )
        val r = records.single()
        assertThat(r.sql).isEqualTo("SELECT 0")
        assertThat(r.expectedError).isEqualTo("path /d/x iteration \${i}")
    }

    @Test
    fun `comma iterators bind each name to the matching value part`() {
        val records = expand(
            """
            foreach type,val integer,42 varchar,'x'

            statement ok
            SELECT ${'$'}{val}::${'$'}{type}, {val}

            endloop
            """.trimIndent(),
        )
        assertThat(records.map { it.sql }).containsExactly(
            "SELECT 42::integer, 42",
            "SELECT 'x'::varchar, 'x'",
        )
    }

    @Test
    fun `foreach special tokens unroll`() {
        val records = expand(
            """
            foreach t <signed> !hugeint

            statement ok
            CREATE TABLE t_${'$'}{t}(i ${'$'}{t});

            endloop
            """.trimIndent(),
        )
        assertThat(records.map { it.sql }).containsExactly(
            "CREATE TABLE t_tinyint(i tinyint);",
            "CREATE TABLE t_smallint(i smallint);",
            "CREATE TABLE t_integer(i integer);",
            "CREATE TABLE t_bigint(i bigint);",
        )
    }

    @Test
    fun `nested loops substitute outermost first`() {
        val records = expand(
            """
            loop i 0 2

            loop j 0 2

            statement ok
            SELECT ${'$'}{i}, ${'$'}{j}

            endloop

            endloop
            """.trimIndent(),
        )
        assertThat(records.map { it.sql })
            .containsExactly("SELECT 0, 0", "SELECT 0, 1", "SELECT 1, 0", "SELECT 1, 1")
    }

    @Test
    fun `records under mode skip are not expanded`() {
        val records = expand(
            """
            statement ok
            SELECT 1;

            mode skip

            statement ok
            SELECT 2;

            mode unskip

            statement ok
            SELECT 3;
            """.trimIndent(),
        )
        assertThat(records.map { it.sql }).containsExactly("SELECT 1;", "SELECT 3;")
    }

    @Test
    fun `require and unsupported records are omitted`() {
        val records = expand(
            """
            require ducklake

            restart

            statement ok
            SELECT 1;
            """.trimIndent(),
        )
        // `restart` is unsupported (skip-don't-throw); `require` is not SQL. Only the statement remains.
        assertThat(records.map { it.sql }).containsExactly("SELECT 1;")
    }

    @Test
    fun `provenance records file and line`() {
        val records = expand(
            """
            statement ok
            SELECT 1;
            """.trimIndent(),
        )
        assertThat(records.single().file).isEqualTo("t")
        assertThat(records.single().line).isEqualTo(1)
    }
}

class TestSltConditions {
    private fun expand(content: String) = SltExpander.expand(SltParser.parse("t", content), "duckdb")

    @Test
    fun `numeric operators and conjunctions follow the DuckDB runner`() {
        val out =
            expand(
                """
                loop i 0 5

                skipif i>2
                statement ok
                SELECT ${'$'}{i}

                onlyif i>=1&&i<>3
                statement ok
                SELECT 'k${'$'}{i}'

                endloop
                """.trimIndent(),
            )
        assertThat(out.map { it.sql }).containsExactly(
            "SELECT 0", "SELECT 1", "SELECT 'k1'", "SELECT 2", "SELECT 'k2'", "SELECT 'k4'",
        )
    }

    @Test
    fun `system names are compared case insensitively and loop conditions need a bound variable`() {
        assertThat(SltConditions.evaluate("DuckDB", "duckdb", emptyMap())).isTrue()
        assertThat(SltConditions.evaluate("postgres", "duckdb", emptyMap())).isFalse()
        assertThat(SltConditions.evaluate("i<=25", "duckdb", mapOf("i" to "25"))).isTrue()
        assertThat(SltConditions.evaluate("i<25", "duckdb", mapOf("i" to "25"))).isFalse()
        org.assertj.core.api.Assertions.assertThatThrownBy { SltConditions.evaluate("i>0", "duckdb", emptyMap()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("loop iterator")
    }
}
