package org.misarch.notification

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.misarch.common.graphql.AuthorizedUser
import org.misarch.notification.graphql.input.UpdateNotificationInput
import org.misarch.notification.persistence.model.NotificationEntity
import org.misarch.notification.persistence.repository.NotificationRepository
import org.misarch.notification.persistence.repository.UserRepository
import org.misarch.notification.service.NotificationService
import java.time.OffsetDateTime
import java.util.UUID

class NotificationServiceTest {

    private val repository = mockk<NotificationRepository>()
    private val userRepository = mockk<UserRepository>()
    private val service = NotificationService(repository, userRepository)
    private val owner = UUID.randomUUID()

    private fun notification(read: Boolean) = NotificationEntity(
        title = "t", body = "b", dateSent = OffsetDateTime.now().minusDays(1),
        dateRead = if (read) OffsetDateTime.now().minusHours(1) else null, userId = owner, id = UUID.randomUUID()
    )

    @ParameterizedTest
    @CsvSource("false,false", "false,true", "true,false", "true,true")
    fun `toggle matrix - dateRead changes only if the state changes, the row is always saved`(read: Boolean, isRead: Boolean) = runTest {
        val entity = notification(read)
        val before = entity.dateRead
        coEvery { repository.findById(entity.id!!) } returns entity
        val saved = slot<NotificationEntity>()
        coEvery { repository.save(capture(saved)) } answers { saved.captured }
        val result = service.updateNotification(UpdateNotificationInput(entity.id!!, isRead), AuthorizedUser(owner, listOf("buyer")))
        coVerify(exactly = 1) { repository.save(any()) }
        when {
            read == isRead -> assertThat(result.dateRead).isEqualTo(before)
            isRead -> assertThat(result.dateRead).isAfter(OffsetDateTime.now().minusMinutes(1))
            else -> assertThat(result.dateRead).isNull()
        }
    }

    @Test
    fun `foreign notifications need admin, employee is not enough`() = runTest {
        val entity = notification(false)
        coEvery { repository.findById(entity.id!!) } returns entity
        coEvery { repository.save(any()) } answers { firstArg() }
        val employee = AuthorizedUser(UUID.randomUUID(), listOf("employee"))
        assertThatThrownBy {
            kotlinx.coroutines.runBlocking { service.updateNotification(UpdateNotificationInput(entity.id!!, true), employee) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("Unauthorized access: ${employee.id} is not an admin")
        val admin = AuthorizedUser(UUID.randomUUID(), listOf("admin"))
        assertThat(service.updateNotification(UpdateNotificationInput(entity.id!!, true), admin).dateRead).isNotNull()
    }
}
