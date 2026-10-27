package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class TestCatalogSortExpressions {
    @Test fun parsedReferencesRespectAsciiIdentityAndDoNotRenameLiteralsOrFunctions() {
        val changed = CatalogSortExpressions.rename("abs(\"Ä\") + \"ä\" + length('Ä?')", "duckdb", "Ä", "new \"name\"")
        assertThat(changed.referenced).isTrue()
        assertThat(changed.expression).contains("abs(", "\"new \"\"name\"\"\"", "\"ä\"", "'Ä?'")
        val functionOnly = "abs(x) + length('abs')"
        assertThat(CatalogSortExpressions.rename(functionOnly, "duckdb", "abs", "other"))
            .isEqualTo(CatalogSortExpressions.Rewrite(functionOnly, false))
        assertThat(CatalogSortExpressions.rename("X + 1", "duckdb", "x", "renamed").referenced).isTrue()
        assertThat(CatalogSortExpressions.rename("Ä + ä", "duckdb", "Ä", "renamed").expression)
            .contains("\"renamed\"", "\"ä\"").doesNotContain("\"Ä\"")
    }

    @Test fun unsafeOrUnqualifiedExpressionsFailBeforeTheyCanBecomeStoredSql() {
        for (expression in listOf("x + ?", "(SELECT x FROM t)", "t.x", "x; DELETE FROM t", "list_transform(xs, lambda x: x + 1)")) {
            assertThatThrownBy { CatalogSortExpressions.rename(expression, "duckdb", "x", "renamed") }
                .describedAs(expression).isInstanceOf(DucklakeInvalidOperationException::class.java)
        }
        assertThatThrownBy { CatalogSortExpressions.rename("(".repeat(129) + "x" + ")".repeat(129), "duckdb", "x", "y") }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
    }
}
