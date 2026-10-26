package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import java.text.MessageFormat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class TestCatalogDiagnosticUrls {
    @Test fun publicDiagnosticExceptionsDoNotExposeUrlCredentials() {
        val urls = listOf(
            "jdbc:postgresql://person:synthetic-secret@db.example/lake?password=query-secret&sslmode=require",
            "jdbc:mysql://db.example/lake?password=encoded%26secret&access_token=token-secret",
            "jdbc:duckdb:quack://db.example:9494?token=quack-secret&metadata_catalog=main",
            "jdbc:sqlserver://db.example;password=semicolon-secret;databaseName=lake",
            "jdbc:mysql:address=(host=db.example)(user=reader)(password=address-secret)/lake",
        )
        for (url in urls) {
            val version = DucklakeUnsupportedCatalogVersionException(url, "unsupported")
            val encrypted = DucklakeEncryptedCatalogUnsupportedException(url)
            for (text in listOf(version.message!!, version.catalogDatabaseUrl, encrypted.message!!, encrypted.catalogDatabaseUrl)) {
                assertThat(text).doesNotContain("synthetic-secret", "query-secret", "encoded%26secret", "token-secret",
                    "quack-secret", "semicolon-secret", "address-secret")
            }
        }
    }

    @Test fun realCatalogInfoLogAndInitializationErrorUseSanitizedUrlWithoutChangingConnectionConfig() {
        TestingDucklakePostgreSqlCatalogServer().use { server ->
            val secret = "diagnostic-url-test-secret"
            val url = server.getJdbcUrl() + "?access_token=$secret"
            val config = DucklakeCatalogConfig().apply {
                catalogDatabaseUrl = url; catalogDatabaseUser = server.getUser(); catalogDatabasePassword = server.getPassword()
                maxCatalogConnections = 1
            }
            val messages = CopyOnWriteArrayList<String>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) {
                    messages.add(MessageFormat.format(record.message, *(record.parameters ?: emptyArray())))
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            val logger = Logger.getLogger(JdbcDucklakeCatalog::class.java.name)
            logger.addHandler(handler)
            try {
                JdbcDucklakeCatalog(config).use { c ->
                    val failure = catchThrowable { c.currentSnapshotId }
                    assertThat(failure).isInstanceOf(DucklakeCatalogNotInitializedException::class.java)
                    assertThat(failure.message).doesNotContain(secret)
                    assertThat(config.catalogDatabaseUrl).isEqualTo(url)
                }
                assertThat(messages.filter { it.contains("Initialized Ducklake JDBC catalog") }).isNotEmpty()
                assertThat(messages.joinToString("\n")).doesNotContain(secret)
            } finally { logger.removeHandler(handler) }
        }
    }
}
