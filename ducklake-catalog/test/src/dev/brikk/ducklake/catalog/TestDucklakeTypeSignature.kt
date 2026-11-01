package dev.brikk.ducklake.catalog

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

internal class TestDucklakeTypeSignature {
    @Test fun quotedNamesRetainCasePunctuationWhitespaceAndUnicode() {
        val parsed = DucklakeTypeSignature.parse(
            "struct<\"a,b\":INT32,\"x:y\":varchar,\"angle<>\":int64,\"q\"\"uote\":int32,\" spaced \":varchar,\"雪\":int32,MiXeD:boolean>")
        assertThat(parsed.name).isEmpty()
        assertThat(parsed.type).isEqualTo("struct")
        assertThat(parsed.children.map { it.name }).containsExactly("a,b", "x:y", "angle<>", "q\"uote", " spaced ", "雪", "MiXeD")
        assertThat(parsed.children.map { it.type }).containsExactly("int32", "varchar", "int64", "int32", "varchar", "int32", "boolean")
    }

    @Test fun nestedContainersAndDecimalCommasAreNotFieldSeparators() {
        val parsed = DucklakeTypeSignature.parse(" MAP < DECIMAL (18, 2), LIST < STRUCT < \"x:y\": DECIMAL(9,3), z:INT32 > > > ")
        assertThat(parsed).isEqualTo(InlinedTypeNode("map", "", listOf(
            InlinedTypeNode("decimal(18,2)", "key"),
            InlinedTypeNode("list", "value", listOf(InlinedTypeNode("struct", "element", listOf(
                InlinedTypeNode("decimal(9,3)", "x:y"), InlinedTypeNode("int32", "z"))))),
        )))
    }

    @Test fun ordinarySignaturesStillParseWithoutChangingNames() {
        assertThat(DucklakeTypeSignature.parse("struct<a:int32,b:list<varchar>,MiXeD:map<varchar,int64>>").children.map { it.name })
            .containsExactly("a", "b", "MiXeD")
        assertThat(DucklakeTypeSignature.parse("UINT128")).isEqualTo(InlinedTypeNode("uint128", ""))
    }

    @Test fun quotingReusesTheExistingQuotedValueEncodingWithoutBackslashEscapes() {
        val names = listOf("a,b", "x:y", "<>", "q\"uote", "back\\slash", "line\nbreak", "\t", "", "NULL", "MiXeD", "雪")
        val signature = "struct<" + names.joinToString(",") { DucklakeQuotedList.encodeValue(it) + ":varchar" } + ">"
        assertThat(DucklakeTypeSignature.parse(signature).children.map { it.name }).isEqualTo(names)
        assertThat(DucklakeTypeSignature.fieldName("MiXeD")).isEqualTo("MiXeD")
        assertThat(DucklakeTypeSignature.fieldName("q\"uote")).isEqualTo("\"q\"\"uote\"")
        assertThat(DucklakeTypeSignature.fieldName("back\\slash")).isEqualTo("\"back\\slash\"")
    }

    @Test fun representationParsingDoesNotInventTypeAdmission() {
        assertThat(DucklakeTypeSignature.parse("variant").type).isEqualTo("variant")
        assertThat(DucklakeTypeSignature.parse("struct<>").children).isEmpty()
        assertThatThrownBy { Final11InlineTypes.validate(DucklakeTypeSignature.parse("variant")) }
            .isInstanceOf(DucklakeInvalidOperationException::class.java)
    }

    @Test fun malformedSignaturesFailRatherThanGuessingFieldNamesOrArity() {
        for (signature in listOf("", " ", "struct<a,b:int32>", "struct<x:y:int32>", "struct<a:int32,>", "struct<\"q:int32>",
            "struct<\"q\"junk:int32>", "list<>", "list<int32,varchar>", "map<int32>", "map<int32,varchar,int64>",
            "int32 trailing", "struct<sp ace:int32>", "struct<雪:int32>", "decimal(39,0)", "decimal(10,11)", "decimal(1,0,0)",
            "int32;SELECT 1", "struct<a:int32")) {
            assertThatThrownBy { DucklakeTypeSignature.parse(signature) }.`as`(signature).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test fun signatureLengthIsBoundedBeforeParsing() {
        assertThatThrownBy { DucklakeTypeSignature.parse("x".repeat(DucklakeTypeSignature.MAX_LENGTH + 1)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("characters")
    }

    @Test fun recursionDepthIsBoundedBeforeStackExhaustion() {
        val count = DucklakeTypeSignature.MAX_DEPTH + 2
        assertThatThrownBy { DucklakeTypeSignature.parse("list<".repeat(count) + "int32" + ">".repeat(count)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("budget")
    }

    @Test fun nodeBudgetAlsoBoundsWideShallowTypes() {
        var balanced = "x"
        repeat(11) { balanced = "map<$balanced,$balanced>" }
        val signature = "map<$balanced,x>" // 4,097 nodes; 14,337 characters; depth 12.
        assertThat(signature.length).isLessThan(DucklakeTypeSignature.MAX_LENGTH)
        assertThatThrownBy { DucklakeTypeSignature.parse(signature) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("budget")
    }
}
