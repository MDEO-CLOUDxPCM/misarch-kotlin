package org.misarch.common.rust

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.graphql.GraphQLErrorException
import org.misarch.common.rust.AxumJson.reqDateTime
import org.misarch.common.rust.AxumJson.reqObject
import org.misarch.common.rust.AxumJson.reqString
import org.misarch.common.rust.AxumJson.reqUnsigned
import org.misarch.common.rust.AxumJson.reqUuid
import org.misarch.common.rust.AxumJson.optString
import java.time.Instant
import java.util.UUID

/** Unit tests of the Rust-compat helpers against the behaviour of the Rust crates (uuid, chrono, mime, axum, serde). */
class RustCompatTest {

    private val id = UUID.fromString("67e55044-10b1-426f-9247-bb680e5fe0c8")

    @Test
    fun `uuid crate accepts hyphenated, simple, braced and urn forms in any case`() {
        assertEquals(id, RustUuid.parse("67e55044-10b1-426f-9247-bb680e5fe0c8"))
        assertEquals(id, RustUuid.parse("67E55044-10B1-426F-9247-BB680E5FE0C8"))
        assertEquals(id, RustUuid.parse("67e5504410b1426f9247bb680e5fe0c8"))
        assertEquals(id, RustUuid.parse("{67e55044-10b1-426f-9247-bb680e5fe0c8}"))
        assertEquals(id, RustUuid.parse("urn:uuid:67e55044-10b1-426f-9247-bb680e5fe0c8"))
        assertNull(RustUuid.parseOrNull("1-1-1-1-1"))
        assertNull(RustUuid.parseOrNull("{67e5504410b1426f9247bb680e5fe0c8}"))
    }

    @Test
    fun `uuid crate error messages`() {
        fun msg(s: String) = assertThrows(RustUuid.ParseException::class.java) { RustUuid.parse(s) }.message
        assertEquals(
            "invalid character: expected an optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], found `x` at 1",
            msg("x")
        )
        assertEquals("invalid length: expected length 32 for simple format, found 3", msg("abc"))
        assertEquals("invalid group count: expected 5, found 2", msg("67e55044-10b1"))
        assertEquals("invalid group length in group 1: expected 4, found 3", msg("67e55044-10b-1426f-9247-bb680e5fe0c8"))
        assertEquals("invalid group length in group 4: expected 12, found 13", msg("67e55044-10b1-426f-9247-bb680e5fe0c8a"))
        assertEquals(
            "invalid character: expected an optional prefix of `urn:uuid:` followed by [0-9a-fA-F-], found `g` at 10",
            msg("{67e55044g10b1-426f-9247-bb680e5fe0c8}")
        )
    }

    @Test
    fun `chrono formats`() {
        assertEquals("2026-10-13T12:00:00+00:00", RustDateTime.toRfc3339(Instant.parse("2026-10-13T12:00:00Z")))
        assertEquals("2026-10-13T12:00:00.120+00:00", RustDateTime.toRfc3339(Instant.parse("2026-10-13T12:00:00.12Z")))
        assertEquals("2026-10-13T12:00:00.123456Z", RustDateTime.toSerde(Instant.parse("2026-10-13T12:00:00.123456Z")))
        assertEquals("2026-10-13T12:00:00.000000001Z", RustDateTime.toSerde(Instant.parse("2026-10-13T12:00:00.000000001Z")))
        assertEquals(0, RustDateTime.now().nano % 1_000_000)
        assertEquals(Instant.parse("2026-10-13T10:00:00Z"), RustDateTime.parseOrNull("2026-10-13T12:00:00+02:00"))
        assertEquals(Instant.parse("2026-10-13T12:00:00Z"), RustDateTime.parseOrNull("2026-10-13 12:00:00z"))
        assertNull(RustDateTime.parseOrNull("2026-10-13"))
    }

    @Test
    fun `mime parsing like the mime crate`() {
        assertEquals(RustMime("image", "png", null), RustMime.parse("image/PNG"))
        assertEquals("svg", RustMime.parse("image/svg+xml")!!.subtype)
        assertEquals("vnd.api", RustMime.parse("application/vnd.api+json")!!.subtype)
        assertEquals("a+b", RustMime.parse("application/a+b+json")!!.subtype)
        assertEquals("plain", RustMime.parse("text/plain; charset=utf-8")!!.subtype)
        assertEquals("", RustMime.parse("image/")!!.subtype)
        assertNull(RustMime.parse("image"))
        assertNull(RustMime.parse("/png"))
        assertNull(RustMime.parse("image/png "))
        assertNull(RustMime.parse("image/png; charset"))
        assertTrue(RustMime.parse("application/json")!!.isJson)
        assertTrue(RustMime.parse("application/cloudevents+json")!!.isJson)
        assertTrue(RustMime.parse("application/json;charset=utf-8")!!.isJson)
        assertFalse(RustMime.parse("text/json")!!.isJson)
    }

    @Test
    fun `axum json status codes and serde accessors`() {
        assertEquals(AxumJson.Rejected(415), AxumJson.parse(null, "{}".toByteArray()))
        assertEquals(AxumJson.Rejected(415), AxumJson.parse("text/plain", "{}".toByteArray()))
        assertEquals(AxumJson.Rejected(400), AxumJson.parse("application/json", null))
        assertEquals(AxumJson.Rejected(400), AxumJson.parse("application/json", "{".toByteArray()))
        assertEquals(AxumJson.Rejected(400), AxumJson.parse("application/json", "{} x".toByteArray()))
        assertEquals(AxumJson.Rejected(422), AxumJson.parse("application/json", """{"a":1,"a":2}""".toByteArray()))
        assertEquals(AxumJson.Rejected(413), AxumJson.parse("application/json", ByteArray(AxumJson.BODY_LIMIT + 1) { ' '.code.toByte() }))
        val root = (AxumJson.parse(
            "application/cloudevents+json",
            """{"topic":"t","data":{"id":"$id","n":3,"neg":-1,"f":1.0,"s":null,"d":"2026-10-13T12:00:00Z"},"x":[1]}""".toByteArray()
        ) as AxumJson.Parsed).root
        val data = root.reqObject("data")
        assertEquals("t", root.reqString("topic"))
        assertEquals(id, data.reqUuid("id"))
        assertEquals(3, data.reqUnsigned("n").toInt())
        assertNull(data.optString("s"))
        assertNull(data.optString("missing"))
        assertEquals(Instant.parse("2026-10-13T12:00:00Z"), data.reqDateTime("d"))
        assertNull(AxumJson.decode { data.reqUnsigned("neg") })
        assertNull(AxumJson.decode { data.reqUnsigned("f") })
        assertNull(AxumJson.decode { data.reqString("s") })
        assertNull(AxumJson.decode { data.reqString("n") })
        assertNull(AxumJson.decode { data.reqUuid("missing") })
        assertNull(AxumJson.decode { data.reqUnsigned("n", AxumJson.U16_MAX.shiftRight(15)) })
    }

    @Test
    fun `authorized user header parsing and authorize_user`() {
        val user = RustAuthorization.parseHeader("""{"id":"$id","roles":["buyer"],"extra":1}""")
        assertEquals(AuthorizedUser(id, listOf("buyer")), user)
        assertNull(RustAuthorization.parseHeader("""{"id":"$id","roles":["buyer","offline_access"]}"""))
        assertNull(RustAuthorization.parseHeader("""{"id":"$id"}"""))
        assertNull(RustAuthorization.parseHeader("""{"id":"nope","roles":[]}"""))
        assertNull(RustAuthorization.parseHeader("""{"id":"$id","roles":["Buyer"]}"""))
        assertNull(RustAuthorization.parseHeader("not json"))
        assertNull(RustAuthorization.parseHeader("""{"id":"$id","roles":["buyer"]}ü"""))
        assertEquals(AuthorizedUser(id, emptyList()), RustAuthorization.parseHeader("""{"roles":[],"id":"${id.toString().uppercase()}"}"""))

        RustAuthorization.authorize(AuthorizedUser(id, listOf("buyer")), id)
        RustAuthorization.authorize(AuthorizedUser(id, listOf("employee")), UUID.randomUUID())
        RustAuthorization.authorize(AuthorizedUser(id, listOf("buyer", "admin")), null)
        RustAuthorization.authorize(AuthorizedUser(id, emptyList()), id)
        assertEquals(
            "Authentication failed. Authorized-User header is not set or could not be parsed.",
            assertThrows(GraphQLErrorException::class.java) { RustAuthorization.authorize(null, id) }.message
        )
        assertEquals(
            "Authentication failed for user of UUID: `$id`. Operation not permitted.",
            assertThrows(GraphQLErrorException::class.java) {
                RustAuthorization.authorize(AuthorizedUser(id, listOf("buyer")), null)
            }.message
        )
    }

    @Test
    fun `mongodb-cursor-pagination semantics`() = runBlocking {
        val rows = (1..10).toList()
        suspend fun page(first: Long?, skip: Long?, data: List<Int> = rows) = MongoCursorPagination.find(
            first, skip, { data.size.toLong() },
            { offset, limit -> data.drop((offset ?: 0).toInt()).take(limit.toInt()) }
        )
        assertEquals(MongoCursorPagination.Page(emptyList<Int>(), false, 0), page(5, 2, emptyList()))
        assertEquals(MongoCursorPagination.Page((1..10).toList(), false, 10), page(null, null))
        assertEquals(MongoCursorPagination.Page((1..3).toList(), true, 10), page(3, 0))
        assertEquals(MongoCursorPagination.Page((1..10).toList(), false, 10), page(10, null))
        assertEquals(MongoCursorPagination.Page(emptyList<Int>(), true, 10), page(0, null))
        // skip > 0: >= L+2 remaining
        assertEquals(MongoCursorPagination.Page((3..5).toList(), true, 10), page(3, 2))
        // skip > 0: exactly L+1 remaining -> L+1 nodes, no next page (off-by-one)
        assertEquals(MongoCursorPagination.Page((7..10).toList(), false, 10), page(3, 6))
        // skip > 0: <= L remaining
        assertEquals(MongoCursorPagination.Page((8..10).toList(), false, 10), page(3, 7))
        assertEquals(MongoCursorPagination.Page(emptyList<Int>(), false, 10), page(3, 20))
        val many = (1..40).toList()
        assertEquals(25, page(null, null, many).nodes.size)
    }
}
