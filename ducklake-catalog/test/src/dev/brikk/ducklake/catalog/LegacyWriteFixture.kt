package dev.brikk.ducklake.catalog

/** Current pinned producer initializes real 1.0 metadata; this never enables 1.1 library writes. */
internal interface LegacyWriteFixture : AutoCloseable {
    fun seed()
    fun reader(): JdbcDucklakeCatalog
    fun metadata(sql: String)
    fun native(sql: String): List<List<String?>>

    class Duck(route: Final11ReadRoute) : LegacyWriteFixture {
        private val fixture = Final11DuckDbBackendFixture(route, formatVersion = "1.0")
        override fun seed() = fixture.native("SELECT 1")
        override fun reader() = fixture.reader(enableFinal11Reads = false)
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun native(sql: String) = fixture.nativeRows(sql)
        override fun close() = fixture.close()
    }
    class Postgres : LegacyWriteFixture {
        private val fixture = Final11PostgresFixture(formatVersion = "1.0")
        override fun seed() = fixture.native("SELECT 1")
        override fun reader() = fixture.reader(enableFinal11Reads = false, maxConnections = 1)
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun native(sql: String) = fixture.nativeRows(sql)
        override fun close() = fixture.close()
    }
    class MySql : LegacyWriteFixture {
        private val fixture = Final11MySqlFixture()
        override fun seed() = fixture.native("SELECT 1", version = "1.0")
        override fun reader() = JdbcDucklakeCatalog(fixture.readerConfig().apply { enableFinal11Reads = false; maxCatalogConnections = 1 })
        override fun metadata(sql: String) = fixture.metadata(sql)
        override fun native(sql: String) = fixture.nativeRows(sql, version = "1.0")
        override fun close() = fixture.close()
    }
}
