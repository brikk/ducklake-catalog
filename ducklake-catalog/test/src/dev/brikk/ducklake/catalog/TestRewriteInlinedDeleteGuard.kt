package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.ResultQuery
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.sql.DriverManager

internal class TestRewriteInlinedDeleteGuard {
    @Test fun sourceIdsAreChunkedAndEachQueryTransfersAtMostOneNumericPair() = database { ctx ->
        ctx.execute("INSERT INTO inline_deletes VALUES(1024,2),(1024,3)")
        val metadata = Recording()
        assertThatThrownBy {
            RewriteInlinedDeleteGuard.check(ctx, metadata, "inline_deletes", (0L..1024L).toCollection(linkedSetOf()), 1)
        }.isInstanceOf(LogicalConflictException::class.java).hasMessageContaining("1024")
        assertThat(metadata.sql).hasSize(5).allSatisfy { sql ->
            assertThat(sql).contains("limit 1").doesNotContain("select *")
        }
        assertThat(metadata.bindings).allSatisfy { assertThat(it).isLessThanOrEqualTo(260) }
    }

    @Test fun alreadyMaterializedAndUnrelatedDeletesDoNotConflict() = database { ctx ->
        ctx.execute("INSERT INTO inline_deletes VALUES(4,1),(99,2),(99,NULL)")
        RewriteInlinedDeleteGuard.check(ctx, DirectMetadataQuery(), "inline_deletes", setOf(4), 1)
    }

    @Test fun unknownSelectedSnapshotRejectsInsteadOfAssumingOldData() = database { ctx ->
        for (value in listOf("NULL", "-1")) {
            ctx.execute("DELETE FROM inline_deletes")
            ctx.execute("INSERT INTO inline_deletes VALUES(4,$value)")
            assertThatThrownBy { RewriteInlinedDeleteGuard.check(ctx, DirectMetadataQuery(), "inline_deletes", setOf(4), 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("inlined")
        }
    }

    @Test fun unknownOwnerCannotProveTheSelectedSourcesAreUnaffected() = database { ctx ->
        for (value in listOf("NULL", "-1")) {
            ctx.execute("DELETE FROM inline_deletes")
            ctx.execute("INSERT INTO inline_deletes VALUES($value,1)")
            assertThatThrownBy { RewriteInlinedDeleteGuard.check(ctx, DirectMetadataQuery(), "inline_deletes", setOf(4), 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("owner")
        }
    }

    @Test fun emptySourceSelectionDoesNotReadTheLedger() = database { ctx ->
        val metadata = Recording()
        RewriteInlinedDeleteGuard.check(ctx, metadata, "inline_deletes", emptySet(), 1)
        assertThat(metadata.sql).isEmpty()
    }

    @Test fun queryFailuresPropagateWithoutPretendingThereWereNoDeletes() = database { ctx ->
        val failure = DataAccessException("injected inline-delete read failure")
        val metadata = object : MetadataQuery by DirectMetadataQuery() {
            override fun <R : Record> fetchOne(dsl: DSLContext, query: ResultQuery<R>): R? = throw failure
        }
        assertThatThrownBy { RewriteInlinedDeleteGuard.check(ctx, metadata, "inline_deletes", setOf(4), 1) }.isSameAs(failure)
    }

    private class Recording : MetadataQuery by DirectMetadataQuery() {
        val sql = mutableListOf<String>()
        val bindings = mutableListOf<Int>()
        override fun <R : Record> fetchOne(dsl: DSLContext, query: ResultQuery<R>): R? {
            sql += dsl.renderInlined(query).lowercase()
            bindings += query.bindValues.size
            return DirectMetadataQuery().fetchOne(dsl, query)
        }
    }

    private fun database(block: (DSLContext) -> Unit) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val ctx = DSL.using(connection, SQLDialect.DUCKDB)
            ctx.execute("CREATE TABLE inline_deletes(file_id BIGINT,begin_snapshot BIGINT)")
            block(ctx)
        }
    }
}
