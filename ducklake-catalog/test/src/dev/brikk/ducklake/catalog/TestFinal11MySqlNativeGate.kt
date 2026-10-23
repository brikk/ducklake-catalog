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

/** Strict acceptance, not expected-error tests. The maintained native build currently fails both. */
class TestFinal11MySqlNativeGate {
    @Test
    fun nativeMigrationFrom10MustSucceedWithoutRetaggingOrLibrarySqlRepair() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(i INTEGER); INSERT INTO lake.t VALUES(7)", version = "1.0")
            f.native("SELECT * FROM lake.t", migrate = true)
            assertThat(f.rows("SELECT value FROM ducklake_metadata WHERE `key`='version'").single()).containsExactly("1.1")
        }
    }

    @Test
    fun nativeFreshNestedInlineStorageMustRoundTripBeforeOpeningTheReaderGuard() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(a INTEGER)); INSERT INTO lake.t VALUES({'a':42}); SELECT * FROM lake.t")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            assertThat(f.rows("SELECT count(*) FROM $physical").single()).containsExactly("1")
        }
    }
}
