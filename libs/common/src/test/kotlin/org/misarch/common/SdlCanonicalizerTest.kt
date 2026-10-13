package org.misarch.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.misarch.common.testing.SdlCanonicalizer

class SdlCanonicalizerTest {

    private val a = """
        schema @link(url: "https://specs.apollo.dev/federation/v2.5", import: ["@key"]) { query: Query }
        directive @key(fields: FieldSet!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
        scalar FieldSet
        type Query { _service: _Service! "Get a user" user(id: UUID!, b: Int = 1): User }
        "A user." type User @key(fields: "id") { name: String id: UUID! }
        scalar UUID
    """.trimIndent()

    private val b = "extend schema @link(url: \"https://specs.apollo.dev/federation/v2.3\", import: [\"@key\", \"@shareable\"])\n" +
        "scalar UUID @specifiedBy(url: \"x\")\n" +
        "\"\"\"\n  A user.\n\"\"\"\n" +
        "type User @key(fields: \"id\", resolvable: true) {\n  id: UUID!\n  name: String\n}\n" +
        "extend type User @shareable\n" +
        "type Query { \"Get   a user\" user(b: Int = 1, id: UUID!): User }\n"

    @Test
    fun `ignores ordering, federation boilerplate and whitespace`() {
        val expected = SdlCanonicalizer.canonicalize(a)
        val other = SdlCanonicalizer.canonicalize(b.replace("extend type User @shareable", ""))
        assertEquals(expected, other)
    }

    @Test
    fun `detects directive differences`() {
        assertNotEquals(SdlCanonicalizer.canonicalize(a), SdlCanonicalizer.canonicalize(b))
    }
}
