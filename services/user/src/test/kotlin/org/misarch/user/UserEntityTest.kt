package org.misarch.user

import com.expediagroup.graphql.generator.execution.OptionalInput
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.misarch.common.event.EventJson
import org.misarch.user.graphql.ifPresent
import org.misarch.user.graphql.model.Gender
import org.misarch.user.persistence.model.UserEntity
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class UserEntityTest {

    @Test
    fun `event payload has the original keys and an ISO offset date time with stripped trailing zeros`() {
        val id = UUID.randomUUID()
        val entity = UserEntity(
            username = "ada", firstName = "Ada", lastName = "Lovelace", birthday = null, gender = Gender.FEMALE,
            dateJoined = OffsetDateTime.of(2024, 5, 1, 10, 20, 30, 123_450_000, ZoneOffset.UTC), id = id
        )
        val json = EventJson.toJson(entity.toEventDTO())
        assertThat(json).isEqualTo(
            """{"id":"$id","username":"ada","firstName":"Ada","lastName":"Lovelace","dateJoined":"2024-05-01T10:20:30.12345Z"}"""
        )
    }

    @Test
    fun `optional input distinguishes undefined, explicit null and value`() {
        val seen = mutableListOf<String?>()
        OptionalInput.Undefined.ifPresent { seen += "undefined:$it" }
        OptionalInput.Defined<String?>(null).ifPresent { seen += "null:$it" }
        OptionalInput.Defined("x").ifPresent { seen += "value:$it" }
        assertThat(seen).containsExactly("null:null", "value:x")
    }
}
