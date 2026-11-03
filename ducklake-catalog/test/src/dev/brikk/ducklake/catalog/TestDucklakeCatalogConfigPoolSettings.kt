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
import java.time.Duration

/**
 * The metadata connection pool knobs on [DucklakeCatalogConfig] reach HikariCP, and the defaults
 * are the ones this library has always run with (10 max, 1 idle, 30 s acquisition timeout,
 * Hikari's own idle/lifetime defaults). An in-memory DuckDB URL is enough: pool sizing is
 * backend-independent and nothing here reads the catalog.
 */
class TestDucklakeCatalogConfigPoolSettings {

    private fun config(configure: DucklakeCatalogConfig.() -> Unit = {}) = DucklakeCatalogConfig().apply {
        catalogDatabaseUrl = "jdbc:duckdb:"
        configure()
    }

    @Test
    fun defaultsAreUnchanged() {
        JdbcDucklakeCatalog(config()).use { catalog ->
            val pool = catalog.hikariDataSource
            assertThat(pool.maximumPoolSize).isEqualTo(10)
            assertThat(pool.minimumIdle).isEqualTo(1)
            assertThat(pool.connectionTimeout).isEqualTo(30_000L)
            assertThat(pool.idleTimeout).isEqualTo(600_000L)
            assertThat(pool.maxLifetime).isEqualTo(1_800_000L)
        }
    }

    @Test
    fun poolKnobsReachHikari() {
        val cfg = config {
            maxCatalogConnections = 4
            minIdleCatalogConnections = 0
            catalogConnectionTimeout = Duration.ofSeconds(5)
            catalogConnectionIdleTimeout = Duration.ofSeconds(45)
            catalogConnectionMaxLifetime = Duration.ofMinutes(5)
        }
        JdbcDucklakeCatalog(cfg).use { catalog ->
            val pool = catalog.hikariDataSource
            assertThat(pool.maximumPoolSize).isEqualTo(4)
            assertThat(pool.minimumIdle).`as`("an idle catalog may hold no metadata connection at all").isZero()
            assertThat(pool.connectionTimeout).isEqualTo(5_000L)
            assertThat(pool.idleTimeout).isEqualTo(45_000L)
            assertThat(pool.maxLifetime).isEqualTo(300_000L)
        }
    }
}
