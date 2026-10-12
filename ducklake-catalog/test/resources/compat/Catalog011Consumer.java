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
package dev.brikk.ducklake.catalog.compat;

import dev.brikk.ducklake.catalog.DucklakeSchema;
import dev.brikk.ducklake.catalog.DucklakeDataFile;
import dev.brikk.ducklake.catalog.DucklakeFileColumnStats;
import dev.brikk.ducklake.catalog.DucklakeColumnStats;
import java.util.UUID;

/** Compile against the PUBLISHED 0.11.0 JAR, not the project-under-test. */
public final class Catalog011Consumer {
    public static Object[] read() {
        DucklakeSchema schema = new DucklakeSchema(1L, new UUID(0L, 1L), 2L, null, "main", null, null);
        DucklakeDataFile file = new DucklakeDataFile(
            1L, 2L, 3L, null, 0L, "rewrite.parquet", true, "parquet", 4L, 100L, 10L,
            null, null, null, null, null, null, null, null, null);
        DucklakeDataFile shortFile = new DucklakeDataFile(
            2L, 2L, 3L, null, 0L, "legacy.parquet", true, "parquet", 4L, 100L, 10L,
            null, null, null, null, null, null);
        DucklakeFileColumnStats fileStats = new DucklakeFileColumnStats(1L, 16L, null, null, "a", "z", null);
        DucklakeColumnStats stats = new DucklakeColumnStats(1L, null, null, 16L, null, null);
        // Freeze accessor descriptors as well as the constructor descriptors.
        if (!schema.schemaName().equals("main") || file.rowIdStart() != null || shortFile.mappingId() != null
                || fileStats.containsNan() != null || stats.totalNullCount() != null) {
            throw new AssertionError("Legacy null/accessor contract changed");
        }
        return new Object[] {schema, file, shortFile, fileStats, stats};
    }
}
