package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.jooq.ExecuteContext
import org.jooq.ExecuteListener
import org.jooq.SQLDialect
import org.jooq.conf.Settings
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.jooq.impl.DefaultExecuteListenerProvider
import org.junit.jupiter.api.Test
import java.sql.DriverManager

internal class TestCatalogColumnTypes {
    @Test fun logicalSnapshotSelectsOneVersionAndExcludesFutureRetiredAndOtherTables() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column VALUES(1,'float64',9,20,NULL,NULL),(1,'float32',9,1,20,NULL)," +
            "(2,'varchar',9,2,8,NULL),(3,'int64',9,30,NULL,NULL),(4,'blob',10,1,NULL,NULL),(5,'int32',9,1,NULL,NULL)")
        assertThat(CatalogColumnTypes.load(ctx, 9, 2)).isEqualTo(mapOf(1L to "float32", 2L to "varchar", 5L to "int32"))
        assertThat(CatalogColumnTypes.load(ctx, 9, 20)).isEqualTo(mapOf(1L to "float64", 5L to "int32"))
        assertThat(CatalogColumnTypes.load(ctx, 9, 30)).isEqualTo(mapOf(1L to "float64", 3L to "int64", 5L to "int32"))
    }

    @Test fun nestedLeafIdentitiesAreNotDiscardedByARootOnlyFilter() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column VALUES(1,'struct',9,1,NULL,NULL),(2,'list',9,1,NULL,1)," +
            "(3,'float64',9,1,NULL,2),(4,'int64',9,1,NULL,1)")
        assertThat(CatalogColumnTypes.load(ctx, 9, 1)).isEqualTo(mapOf(1L to "struct", 2L to "list", 3L to "float64", 4L to "int64"))
    }

    @Test fun wideSchemasUseBoundedKeysetQueries() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column SELECT i,'int64',9,1,NULL,NULL FROM range(1025)t(i)")
        val (recording, sql) = recording(ctx)
        assertThat(CatalogColumnTypes.load(recording, 9, 1)).hasSize(1025).containsEntry(1024L, "int64")
        assertThat(sql).hasSize(2).allSatisfy { assertThat(it).contains("limit 1025", "substring") }
        assertThat(sql.last()).contains("coltype.column_id > 1023")
    }

    @Test fun duplicateVisibleIdentityIsNotResolvedByPhysicalOrder() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column VALUES(1,'float32',9,1,NULL,NULL),(1,'float64',9,2,NULL,NULL)")
        assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 2) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Duplicate")
    }

    @Test fun duplicateIdentityAtThePageBoundaryFailsBeforeItCanBeSkipped() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column SELECT i,'int64',9,1,NULL,NULL FROM range(1024)t(i)")
        ctx.execute("INSERT INTO ducklake_column VALUES(1023,'float64',9,1,NULL,NULL)")
        assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 1) }
            .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("Duplicate")
    }

    @Test fun nullAndNegativeIdentitiesDoNotBecomeZeroOrDisappearBehindACursor() = database { ctx ->
        for (id in listOf("NULL", "-1")) {
            ctx.execute("DELETE FROM ducklake_column")
            ctx.execute("INSERT INTO ducklake_column VALUES($id,'int64',9,1,NULL,NULL)")
            assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("identity")
        }
    }

    @Test fun nullEmptyAndOversizedTypesCannotQualifyBounds() = database { ctx ->
        for (type in listOf("NULL", "''", "repeat('x',257)")) {
            ctx.execute("DELETE FROM ducklake_column")
            ctx.execute("INSERT INTO ducklake_column VALUES(1,$type,9,1,NULL,NULL)")
            assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 1) }
                .isInstanceOf(DucklakeCatalogCorruptionException::class.java).hasMessageContaining("type")
        }
    }

    @Test fun typeTextIsCappedInSqlRatherThanAfterUnboundedTransfer() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column VALUES(1,repeat('x',10000),9,1,NULL,NULL)")
        val (recording, sql) = recording(ctx)
        assertThatThrownBy { CatalogColumnTypes.load(recording, 9, 1) }.isInstanceOf(DucklakeCatalogCorruptionException::class.java)
        assertThat(sql).singleElement().asString().contains("257", "substring", "limit 1025")
    }

    @Test fun theTraversalBudgetAcceptsExactlyItsMaximum() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column SELECT i,'int64',9,1,NULL,NULL FROM range(65536)t(i)")
        assertThat(CatalogColumnTypes.load(ctx, 9, 1)).hasSize(65536).containsEntry(65535L, "int64")
    }

    @Test fun oversizedTraversalFailsInsteadOfReturningAPartialTypeMap() = database { ctx ->
        ctx.execute("INSERT INTO ducklake_column SELECT i,'int64',9,1,NULL,NULL FROM range(65537)t(i)")
        assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 1) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java).hasMessageContaining("budget")
    }

    @Test fun missingVisibleColumnsReturnAnEmptyMap() = database { ctx ->
        assertThat(CatalogColumnTypes.load(ctx, 9, 1)).isEmpty()
    }

    @Test fun queryErrorsAreNotReclassifiedAsAnEmptyTypeMap() = database { ctx ->
        ctx.execute("DROP TABLE ducklake_column")
        assertThatThrownBy { CatalogColumnTypes.load(ctx, 9, 1) }.isInstanceOf(DataAccessException::class.java)
    }

    private fun recording(ctx: DSLContext): Pair<DSLContext, MutableList<String>> {
        val sql = mutableListOf<String>()
        val listener = object : ExecuteListener {
            override fun renderEnd(context: ExecuteContext) {
                context.query()?.let { sql += ctx.renderInlined(it).lowercase().replace("\"", "") }
            }
        }
        return DSL.using(ctx.configuration().deriveAppending(DefaultExecuteListenerProvider(listener))) to sql
    }

    private fun database(block: (DSLContext) -> Unit) {
        DriverManager.getConnection("jdbc:duckdb:").use { connection ->
            val ctx = DSL.using(connection, SQLDialect.DUCKDB, Settings().withRenderSchema(false))
            ctx.execute("CREATE TABLE ducklake_column(column_id BIGINT,column_type VARCHAR,table_id BIGINT," +
                "begin_snapshot BIGINT,end_snapshot BIGINT,parent_column BIGINT)")
            block(ctx)
        }
    }
}
