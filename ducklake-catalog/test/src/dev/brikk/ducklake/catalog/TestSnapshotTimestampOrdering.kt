/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Instant
import java.util.function.Supplier

class TestSnapshotTimestampOrdering {
    @Test
    fun timestampOrderingTieBreaksAndPinnedVisibilityDoNotAssumeMonotonicWriterClocks(@TempDir root: Path) {
        val metadata = root.resolve("meta.db")
        val data = root.resolve("data")
        DriverManager.getConnection("jdbc:duckdb:").use { c -> c.createStatement().use { st ->
            st.execute("INSTALL ducklake; LOAD ducklake; ATTACH 'ducklake:$metadata' AS lake (DATA_PATH '$data'); DETACH lake")
        } }
        DriverManager.getConnection("jdbc:duckdb:$metadata").use { c -> c.createStatement().use { st ->
            st.execute("UPDATE ducklake_snapshot SET snapshot_time=TIMESTAMPTZ '1999-01-01' WHERE snapshot_id=0")
            // Valid metadata-only snapshots with deliberately non-monotonic times and a tie.
            st.execute("INSERT INTO ducklake_snapshot SELECT v.id,v.ts,s.schema_version,s.next_catalog_id,s.next_file_id " +
                "FROM (VALUES (1,TIMESTAMPTZ '2003-01-01'),(2,TIMESTAMPTZ '2002-01-01'),(3,TIMESTAMPTZ '2002-01-01')) v(id,ts) " +
                "CROSS JOIN ducklake_snapshot s WHERE s.snapshot_id=0")
            st.execute("INSERT INTO ducklake_snapshot_changes(snapshot_id,changes_made) VALUES (1,''),(2,''),(3,'')")
        } }
        val config = DucklakeCatalogConfig().apply {
            catalogDatabaseUrl = "jdbc:duckdb:$metadata"
            dataPath = data.toString()
            maxCatalogConnections = 1
        }
        JdbcDucklakeCatalog(config).use { reader -> JdbcDucklakeCatalog(config).use { writer ->
            val broadCutoff = Instant.parse("2100-01-01T00:00:00Z")
            assertThat(reader.currentSnapshotId).isEqualTo(3L)
            assertThat(reader.getSnapshotAtOrBefore(broadCutoff)!!.snapshotId).isEqualTo(1L)
            assertThat(reader.getSnapshotAtOrBefore(Instant.parse("2002-01-01T00:00:00Z"))!!.snapshotId).isEqualTo(3L)
            assertThat(reader.getSnapshotAtOrBefore(Instant.parse("1990-01-01T00:00:00Z"))).isNull()
            reader.readSession(Supplier {
                assertThat(reader.currentSnapshotId).isEqualTo(3L)
                writer.createSchema("committed_after_timestamp_pin")
                assertThat(writer.currentSnapshotId).isEqualTo(4L)
                assertThat(reader.currentSnapshotId).isEqualTo(3L)
                assertThat(reader.getSnapshot(4L)).isNull()
                assertThat(reader.getSnapshotAtOrBefore(broadCutoff)!!.snapshotId).isEqualTo(1L)
            })
            assertThat(reader.currentSnapshotId).isEqualTo(4L)
            assertThat(reader.getSnapshotAtOrBefore(broadCutoff)!!.snapshotId).isEqualTo(4L)
        } }
    }
}
