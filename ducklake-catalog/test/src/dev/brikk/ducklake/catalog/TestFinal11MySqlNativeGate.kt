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

/** Deferred native writer/migration acceptance, not expected-error tests or existing-catalog read qualification. */
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
    fun nativeFreshNestedInlineStorageMustRoundTripBeforeQualifyingThatType() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(s STRUCT(a INTEGER)); INSERT INTO lake.t VALUES({'a':42}); SELECT * FROM lake.t")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            assertThat(f.rows("SELECT count(*) FROM $physical").single()).containsExactly("1")
        }
    }

    @Test
    fun nativeRetypeMustCommitBeforeQualifyingMySqlSchemaEvolution() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v TINYINT); INSERT INTO lake.t VALUES(7)")
            f.native("ALTER TABLE lake.t ALTER v TYPE INTEGER")
        }
    }

    @Test
    fun nativeHugeintStorageMustNotSilentlyRoundIntegerIdentity() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v HUGEINT); INSERT INTO lake.t VALUES(9007199254740993)")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            assertThat(f.rows("SELECT cast(v AS DECIMAL(38,0)) FROM $physical").single()).containsExactly("9007199254740993")
        }
    }

    @Test
    fun nativeTimestampStorageMustPreserveMicroseconds() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v TIMESTAMP); INSERT INTO lake.t VALUES(TIMESTAMP '2020-01-01 12:34:56.123456')")
            val physical = f.rows("SELECT table_name FROM ducklake_inlined_data_tables").single().single()!!
            assertThat(f.rows("SELECT v FROM $physical").single()).containsExactly("2020-01-01 12:34:56.123456")
        }
    }

    @Test
    fun nativeJsonStorageMustMaterializeBeforeQualifyingThatType() {
        Final11MySqlFixture().use { f ->
            f.native("CREATE TABLE lake.t(v JSON); INSERT INTO lake.t VALUES('{\"x\":null}'::JSON)")
        }
    }
}
