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
    fun `statement maybe is an error kind with no expectation`() {
        val records = expand(
            """
            statement maybe
            SET threads=4;
            """.trimIndent(),
        )
        assertThat(records.single().kind).isEqualTo(RecordKind.ERROR)
        assertThat(records.single().expectedError).isNull()
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
