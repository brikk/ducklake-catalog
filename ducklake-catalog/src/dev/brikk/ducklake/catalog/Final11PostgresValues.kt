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

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.OffsetTime
import java.time.ZoneOffset
import java.nio.ByteBuffer

/** PostgreSQL's widened/text storage does not enforce the original DuckDB leaf domains. */
internal object Final11PostgresValues {
    const val MAX_BYTES = 1_048_576
    private const val MAX_NODES = 65536
    private val JSON = JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(64).maxStringLength(MAX_BYTES).maxNumberLength(4096).build()).build()
    private val DECIMAL = Regex("decimal\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*\\)")

    fun decode(raw: Any?, type: InlinedTypeNode): Any? {
        val size = when (raw) { is String -> raw.length; is ByteArray -> raw.size; else -> 0 }
        if (size > MAX_BYTES) unsupported("inline value text/byte budget exceeded")
        val value = InlinedValues.decodeQualified(raw, type)
        return try { Normalizer().value(value, type) }
        catch (e: DucklakeException) { throw e }
        catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            throw DucklakeCatalogCorruptionException("Invalid declared PostgreSQL inline value for ${type.name} (${type.type})", e)
        }
    }

    private class Normalizer {
        private var nodes = 0
        fun value(input: Any?, node: InlinedTypeNode): Any? {
            if (++nodes > MAX_NODES) unsupported("inline value node budget exceeded")
            if (input == null) return null
            return when (node.type) {
                "struct" -> {
                    val fields = input as Map<*, *>
                    require(fields.keys == node.children.map { it.name }.toSet()) { "missing/extra STRUCT fields" }
                    node.children.associateTo(linkedMapOf()) { it.name to value(fields[it.name], it) }
                }
                "list" -> (input as List<*>).map { value(it, node.children.single()) }
                "map" -> linkedMapOf<Any, Any?>().also { out ->
                    val identities = hashSetOf<Any>()
                    (input as Map<*, *>).forEach { (key, item) ->
                        val normalized = value(key, node.children[0]) ?: throw IllegalArgumentException("NULL MAP key")
                        require(identities.add(keyIdentity(normalized))) { "duplicate normalized MAP key" }
                        out[normalized] = value(item, node.children[1])
                    }
                }
                else -> scalar(input, node.type)
            }
        }
    }

    /** Native scalar equality: binary content, NaN/signed-zero and normalized INTERVAL comparisons. */
    fun keyIdentity(key: Any): Any = when (key) {
        is ByteArray -> ByteBuffer.wrap(key).asReadOnlyBuffer()
        is Float -> if (key == 0.0f) 0.0f else key
        is Double -> if (key == 0.0) 0.0 else key
        is DucklakeInterval -> BigInteger.valueOf(key.months).multiply(BigInteger.valueOf(30))
            .add(BigInteger.valueOf(key.days)).multiply(BigInteger.valueOf(86_400_000_000L)).add(BigInteger.valueOf(key.micros))
        else -> key
    }

    private fun scalar(value: Any, type: String): Any {
        val decimal = DECIMAL.matchEntire(type)
        if (decimal != null) {
            val scale = decimal.groupValues[2].toInt()
            val result = (value as BigDecimal).setScale(scale, RoundingMode.UNNECESSARY)
            require(result.precision() <= decimal.groupValues[1].toInt()) { "decimal precision overflow" }
            return result
        }
        when {
            type == "json" -> validateJson(value as String)
            type == "date" -> finiteInteger(BigInteger.valueOf((value as LocalDate).toEpochDay()), 32)
            type == "time" -> require((value as LocalTime).nano % 1000 == 0) { "TIME has sub-microsecond data" }
            type == "timetz" -> {
                val time = value as OffsetTime
                require(time.nano % 1000 == 0 && kotlin.math.abs(time.offset.totalSeconds) <= 57599) { "native TIMETZ precision/offset overflow" }
            }
            type == "timestamptz" || type == "timestamptz_ns" -> timestamp((value as OffsetDateTime).toLocalDateTime(), type)
            type.startsWith("timestamp") -> timestamp(value as LocalDateTime, type)
        }
        return value
    }

    private fun timestamp(value: LocalDateTime, type: String) {
        val units = when (type) {
            "timestamp_s" -> 1L
            "timestamp_ms" -> 1000L
            "timestamp_ns", "timestamptz_ns" -> 1_000_000_000L
            else -> 1_000_000L
        }
        val quantum = 1_000_000_000L / units
        require(value.nano % quantum == 0L) { "timestamp precision mismatch" }
        val count = BigInteger.valueOf(value.toEpochSecond(ZoneOffset.UTC)).multiply(BigInteger.valueOf(units)) +
            BigInteger.valueOf(value.nano / quantum)
        finiteInteger(count, 64)
        if (units < 1_000_000L) {
            val micros = BigInteger.valueOf(value.toEpochSecond(ZoneOffset.UTC)).multiply(BigInteger.valueOf(1_000_000)) +
                BigInteger.valueOf(value.nano / 1000L)
            val limit = BigInteger.ONE.shiftLeft(63)
            if (micros <= limit.negate() || micros >= limit - BigInteger.ONE) unsupported("timestamp seconds/millis exceeds qualified native display range")
        }
    }

    private fun finiteInteger(value: BigInteger, bits: Int) {
        val limit = BigInteger.ONE.shiftLeft(bits - 1)
        require(value > limit.negate() && value < limit - BigInteger.ONE) { "native temporal range overflow/infinity sentinel" }
    }

    private fun validateJson(value: String) {
        try {
            JSON.createParser(value).use(::consumeJson)
        } catch (e: StreamConstraintsException) {
            throw DucklakeInvalidOperationException("PostgreSQL inline JSON exceeds the qualified representation budget").also { it.initCause(e) }
        } catch (e: JsonProcessingException) {
            throw DucklakeCatalogCorruptionException("Invalid JSON in PostgreSQL inline storage", e)
        }
    }

    private fun consumeJson(parser: JsonParser) {
        var depth = 0
        var roots = 0
        var tokens = 0
        while (true) {
            val token = parser.nextToken() ?: break
            if (++tokens > MAX_NODES) unsupported("JSON token budget exceeded")
            if (token == JsonToken.START_ARRAY || token == JsonToken.START_OBJECT) {
                if (depth == 0) roots++
                depth++
            } else if (token == JsonToken.END_ARRAY || token == JsonToken.END_OBJECT) depth--
            else if (depth == 0) roots++
            require(roots <= 1) { "trailing JSON value" }
        }
        require(roots == 1 && depth == 0) { "incomplete JSON value" }
    }

    private fun unsupported(message: String): Nothing = throw DucklakeInvalidOperationException("PostgreSQL $message")
}
