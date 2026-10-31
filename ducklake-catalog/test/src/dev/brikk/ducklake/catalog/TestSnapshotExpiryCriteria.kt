package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/** N05 keeps input validation separate from dialect-specific timestamp rendering. */
internal class TestSnapshotExpiryCriteria {
    @Nested inner class Local : FractionalContract({ LegacyWriteFixture.Duck(Final11ReadRoute.LOCAL_DUCKDB) })
    @Nested inner class NativeQuack : FractionalContract({ LegacyWriteFixture.Duck(Final11ReadRoute.NATIVE_QUACK) })
    @Nested inner class JavaQuack : FractionalContract({ LegacyWriteFixture.Duck(Final11ReadRoute.JAVA_QUACK) })
    @Nested inner class Postgres : FractionalContract({ LegacyWriteFixture.Postgres() })
    @Nested inner class MySql : Contract({ LegacyWriteFixture.MySql() })

    abstract class Contract(private val fixture: () -> LegacyWriteFixture) {
        @Test fun conflictingCriteriaRejectEvenAnEmptyVersionsSetWithoutChangingMetadata(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                c.createSchema("first")
                val before = c.listSnapshots()
                for (versions in listOf(setOf(0L), emptySet())) {
                    assertThatThrownBy { c.listExpirableSnapshots(Instant.EPOCH, versions) }
                        .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("mutually exclusive")
                }
                assertThat(c.listSnapshots()).isEqualTo(before)
            }
        }

        @Test fun timestampCutoffsAreStrictUtcAndIndependentOfSnapshotIdOrdering(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                c.createSchema("first")
                c.createSchema("second")
                c.createSchema("latest")
                // Real snapshots, deliberately non-monotonic clocks. Latest ID stays protected even when oldest in time.
                val times = listOf("1969-12-31 23:59:59", "2003-01-01 00:00:00",
                    "2002-01-01 00:00:00", "1960-01-01 00:00:00")
                times.forEachIndexed { id, time ->
                    f.metadata("UPDATE ducklake_snapshot SET snapshot_time='$time' WHERE snapshot_id=$id")
                }
                val before = c.listSnapshots()
                val exact = Instant.parse("2002-01-01T00:00:00Z")
                assertThat(c.listExpirableSnapshots(exact, null)).containsExactly(0L)
                assertThat(c.listExpirableSnapshots(exact.plusNanos(1000), null)).containsExactly(0L, 2L)
                assertThat(c.listExpirableSnapshots(Instant.parse("2002-01-01T02:00:00+02:00"), null)).containsExactly(0L)
                val negative = Instant.parse("1969-12-31T23:59:59Z")
                assertThat(c.listExpirableSnapshots(negative, null)).isEmpty()
                assertThat(c.listExpirableSnapshots(negative.plusNanos(1000), null)).containsExactly(0L)
                assertThat(c.listExpirableSnapshots(Instant.parse("2100-01-01T00:00:00Z"), null)).containsExactly(0L, 1L, 2L)
                assertThat(c.listSnapshots()).isEqualTo(before)
            }
        }

        @Test fun versionAndDefaultSelectionRemainAscendingAndNeverSelectLatest(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                assertThat(c.listExpirableSnapshots(null, null)).isEmpty()
                c.createSchema("first")
                c.createSchema("second")
                c.createSchema("latest")
                assertThat(c.listExpirableSnapshots(null, null)).containsExactly(0L, 1L, 2L)
                assertThat(c.listExpirableSnapshots(null, setOf(999L, 3L, 2L, 0L))).containsExactly(0L, 2L)
                assertThat(c.listExpirableSnapshots(null, emptySet())).isEmpty()
                assertThat(c.listExpirableSnapshots(null, setOf(3L))).isEmpty()
            }
        }
    }

    // The native MySQL snapshot column stores whole seconds. Fractional storage is a separate native gate.
    abstract class FractionalContract(private val fixture: () -> LegacyWriteFixture) : Contract(fixture) {
        @Test fun negativeEpochMicrosecondCutoffPreservesEqualityAndStrictness(): Unit = fixture().use { f ->
            f.seed()
            f.reader().use { c ->
                c.createSchema("latest")
                f.metadata("UPDATE ducklake_snapshot SET snapshot_time='1969-12-31 23:59:59.123456' WHERE snapshot_id=0")
                val exact = Instant.parse("1969-12-31T23:59:59.123456Z")
                assertThat(c.getSnapshot(0L)!!.snapshotTime).isEqualTo(exact)
                assertThat(c.listExpirableSnapshots(exact, null)).isEmpty()
                assertThat(c.listExpirableSnapshots(exact.plusNanos(1000), null)).containsExactly(0L)
            }
        }
    }
}
