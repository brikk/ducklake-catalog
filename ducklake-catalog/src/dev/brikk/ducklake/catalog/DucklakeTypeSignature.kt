package dev.brikk.ducklake.catalog

import java.util.Locale

/**
 * Parser for the catalog's composite `columnType` strings, not SQL or stored DuckLake type syntax.
 * Type keywords are canonicalized; STRUCT field names retain their exact case and characters.
 * Quoted names use [DucklakeQuotedList]'s double-quote/doubled-quote encoding. Unquoted names are
 * ASCII identifiers. Parsing a type does not qualify its values, conversions or backend support.
 */
object DucklakeTypeSignature {
    const val MAX_LENGTH = 16384
    const val MAX_DEPTH = 64
    const val MAX_NODES = 4096
    private val SIMPLE_FIELD = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Root name is empty; LIST children are named `element`, MAP children `key` and `value`.
     * @throws IllegalArgumentException on malformed syntax/arity or an exceeded parsing budget.
     */
    @JvmStatic
    fun parse(signature: String): InlinedTypeNode {
        require(signature.length <= MAX_LENGTH) { "Type signature exceeds $MAX_LENGTH characters" }
        return Parser(signature).parse()
    }

    internal fun fieldName(name: String): String = if (SIMPLE_FIELD.matches(name)) name else DucklakeQuotedList.encodeValue(name)

    private class Parser(private val text: String) {
        private var position = 0
        private var nodes = 0

        fun parse(): InlinedTypeNode = type("", 0).also {
            whitespace()
            require(position == text.length) { "Type signature has trailing syntax at $position" }
        }

        private fun type(name: String, depth: Int): InlinedTypeNode {
            require(depth <= MAX_DEPTH && ++nodes <= MAX_NODES) { "Type signature exceeds depth/node budget" }
            val keyword = word().lowercase(Locale.ROOT)
            return when (keyword) {
                "struct" -> InlinedTypeNode(keyword, name, struct(depth))
                "list" -> {
                    expect('<')
                    val element = type("element", depth + 1)
                    expect('>')
                    InlinedTypeNode(keyword, name, listOf(element))
                }
                "map" -> {
                    expect('<')
                    val key = type("key", depth + 1)
                    expect(',')
                    val value = type("value", depth + 1)
                    expect('>')
                    InlinedTypeNode(keyword, name, listOf(key, value))
                }
                "decimal" -> InlinedTypeNode(decimal(), name)
                else -> InlinedTypeNode(keyword, name)
            }
        }

        private fun struct(depth: Int): List<InlinedTypeNode> {
            expect('<')
            if (consume('>')) return emptyList()
            val fields = mutableListOf<InlinedTypeNode>()
            do {
                val name = field()
                expect(':')
                fields.add(type(name, depth + 1))
            } while (consume(','))
            expect('>')
            return fields
        }

        private fun field(): String {
            whitespace()
            if (peek() != '"') return word()
            val parsed = DucklakeQuotedList.parseValue(text, position)
            position = parsed.endPos
            return parsed.value
        }

        private fun decimal(): String {
            expect('(')
            val precision = digits()
            expect(',')
            val scale = digits()
            expect(')')
            require(precision in 1..38 && scale in 0..precision) { "Invalid decimal precision/scale in type signature" }
            return "decimal($precision,$scale)"
        }

        private fun digits(): Int {
            whitespace()
            val start = position
            while (peek() in '0'..'9') position++
            require(position - start in 1..2) { "Invalid decimal number at $start in type signature" }
            return text.substring(start, position).toInt()
        }

        private fun word(): String {
            whitespace()
            val start = position
            require(nameStart(peek())) { "Expected type/name at $position" }
            while (nameStart(peek()) || peek() in '0'..'9') position++
            return text.substring(start, position)
        }

        private fun nameStart(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char == '_'
        private fun whitespace() { while (position < text.length && text[position].isWhitespace()) position++ }
        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'
        private fun consume(char: Char): Boolean {
            whitespace()
            if (peek() != char) return false
            position++
            return true
        }
        private fun expect(char: Char) { require(consume(char)) { "Expected '$char' at $position in type signature" } }
    }
}
