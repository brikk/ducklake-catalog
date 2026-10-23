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

import java.nio.file.Path

/** Every producer uses the existing locked native fixture; no reader bootstrap or version retag. */
internal interface ReadAcceptanceFixture : AutoCloseable {
    val data: Path
    fun native(sql: String): List<List<String?>>
    fun metadata(sql: String)
    fun reader(): JdbcDucklakeCatalog

    class Duck(route: Final11ReadRoute) : ReadAcceptanceFixture {
        private val fixture = Final11DuckDbBackendFixture(route)
        override val data get() = fixture.data
        override fun native(sql: String) = fixture.nativeRows(sql)
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun reader() = fixture.reader()
        override fun close() = fixture.close()
    }
    class Postgres : ReadAcceptanceFixture {
        private val fixture = Final11PostgresFixture()
        override val data get() = fixture.data
        override fun native(sql: String) = fixture.nativeRows(sql)
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun reader() = fixture.reader()
        override fun close() = fixture.close()
    }
    class MySql : ReadAcceptanceFixture {
        private val fixture = Final11MySqlFixture()
        override val data get() = fixture.data
        override fun native(sql: String) = fixture.nativeRows(sql)
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun reader() = JdbcDucklakeCatalog(fixture.readerConfig())
        override fun close() = fixture.close()
    }
}
