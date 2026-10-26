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

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** DuckLake identifiers fold ASCII only, independently of metadata database collations. */
internal object CatalogIdentifiers {
    fun matches(ctx: DSLContext, field: Field<String>, name: String): Condition {
        var folded = field
        if (ctx.dialect().family() == SQLDialect.MYSQL) {
            for (letter in 'A'..'Z') folded = DSL.replace(folded, DSL.`val`(letter.toString()), DSL.`val`(letter.lowercaseChar().toString()))
        } else folded = DSL.function("translate", SQLDataType.VARCHAR, folded,
            DSL.`val`("ABCDEFGHIJKLMNOPQRSTUVWXYZ"), DSL.`val`("abcdefghijklmnopqrstuvwxyz"))
        return Final11SqlBackend.bytes(ctx, folded).eq(Final11SqlBackend.bytes(ctx, DSL.`val`(Final11InlineTypes.identifierKey(name))))
    }
}
