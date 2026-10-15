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

/**
 * Partition value for a data file. The partitionValue may be null when the source column value is NULL.
 * A stored bucket value is not an equality-class identifier: equal NaNs or nested signed zeros
 * can have different historical hashes. Predicate pruning must conservatively retain all possible
 * matching buckets (including mixed IN lists with an unsafe constant), not rehash stored values.
 */
@JvmRecord
data class DucklakeFilePartitionValue(
    val dataFileId: Long,
    val partitionKeyIndex: Int,
    val partitionValue: String?,
)
