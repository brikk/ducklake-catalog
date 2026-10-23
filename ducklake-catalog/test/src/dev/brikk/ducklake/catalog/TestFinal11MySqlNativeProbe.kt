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

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy

class TestFinal11MySqlNativeProbe {
    @Test
    fun nativeFinal11SetupAndPhysicalValues() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER,s VARCHAR,b BLOB,d DATE); " +
                "INSERT INTO lake.t VALUES(1,'NULL',from_hex('00FF'),DATE '2020-01-01')")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            val types = f.rows("SELECT column_name,data_type,column_type FROM information_schema.columns " +
                "WHERE table_schema=database() AND table_name='$physical' ORDER BY ordinal_position")
            println("R06 native MySQL physical storage: $types")
            assertThat(types).containsExactly(
                listOf("_ducklake_row_id", "bigint", "bigint"), listOf("_ducklake_begin_snapshot", "bigint", "bigint"),
                listOf("_ducklake_end_snapshot", "bigint", "bigint"), listOf("i", "int", "int"),
                listOf("s", "text", "text"), listOf("b", "blob", "blob"), listOf("d", "date", "date"))
            assertThat(f.rows("SELECT i,s,hex(b),d FROM $physical").single()).containsExactly("1", "NULL", "00FF", "2020-01-01")
            JdbcDucklakeCatalog(f.readerConfig().apply { enableFinal11Reads = false }).use { c ->
                assertThatThrownBy { c.currentSnapshotId }.isInstanceOf(DucklakeInvalidOperationException::class.java)
                    .hasMessageContaining("enableFinal11Reads")
            }
        }
    }
}
