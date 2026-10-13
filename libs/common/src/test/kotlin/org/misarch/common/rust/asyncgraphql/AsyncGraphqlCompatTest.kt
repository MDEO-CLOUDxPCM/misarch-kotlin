package org.misarch.common.rust.asyncgraphql

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests of the async-graphql compatibility helpers of the shoppingcart and wishlist ports (moved from their
 * `CompatUnitTest`); the GraphQL/HTTP behaviour is covered by the services' integration tests.
 */
class AsyncGraphqlCompatTest {

    private val id = UUID.fromString("a1a1a1a1-0000-4000-8000-000000000001")

    @Test
    fun `date time is formatted like chrono to_rfc3339`() {
        assertThat(RustOffsetDateTime.format(OffsetDateTime.of(2026, 10, 13, 8, 15, 42, 123_000_000, ZoneOffset.UTC)))
            .isEqualTo("2026-10-13T08:15:42.123+00:00")
        assertThat(RustOffsetDateTime.format(OffsetDateTime.of(2026, 10, 13, 8, 15, 42, 120_000_000, ZoneOffset.UTC)))
            .isEqualTo("2026-10-13T08:15:42.120+00:00")
        assertThat(RustOffsetDateTime.format(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)))
            .isEqualTo("2026-01-02T03:04:05+00:00")
        assertThat(RustOffsetDateTime.format(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneOffset.ofHours(2))))
            .isEqualTo("2026-01-02T01:04:05+00:00")
        assertThat(RustOffsetDateTime.format(OffsetDateTime.of(2026, 1, 2, 3, 4, 5, 1_500, ZoneOffset.UTC)))
            .isEqualTo("2026-01-02T03:04:05.000001500+00:00")
        assertThat(RustOffsetDateTime.now().nano % 1_000_000).isZero()
    }

    @Test
    fun `authorized user header is parsed like serde`() {
        val user = RustAuthorizedUserHeader.parse("""{"id":"$id","roles":["buyer","employee"],"extra":1}""")!!
        assertThat(user.id).isEqualTo(id)
        assertThat(user.roles).containsExactly("buyer", "employee")
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id","roles":[]}""")!!.roles).isEmpty()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"${id.toString().replace("-", "")}","roles":[]}""")).isNotNull
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id"}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"roles":["buyer"]}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id","roles":["BUYER"]}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id","roles":["guest"]}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"1-2-3-4-5","roles":[]}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id","roles":null}""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"$id","roles":[]} x""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("""[]""")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("")).isNull()
        assertThat(RustAuthorizedUserHeader.parse("{\"id\":\"$id\",\"roles\":[\"buyer\"],\"n\":\"ä\"}")).isNull()
        // unlike RustAuthorization.parseHeader (review, invoice, media): a duplicate key is not rejected, the last wins
        assertThat(RustAuthorizedUserHeader.parse("""{"id":"x","id":"$id","roles":[]}""")!!.id).isEqualTo(id)
    }
}
