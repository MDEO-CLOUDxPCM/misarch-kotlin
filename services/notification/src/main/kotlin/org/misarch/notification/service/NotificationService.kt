package org.misarch.notification.service

import org.misarch.common.graphql.AuthorizedUser
import org.misarch.common.persistence.findByIdOrThrow
import org.misarch.notification.graphql.input.NotificationInput
import org.misarch.notification.graphql.input.UpdateNotificationInput
import org.misarch.notification.persistence.model.NotificationEntity
import org.misarch.notification.persistence.repository.NotificationRepository
import org.misarch.notification.persistence.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

/**
 * Service for [NotificationEntity]s
 */
@Service
class NotificationService(
    private val repository: NotificationRepository,
    private val userRepository: UserRepository,
    private val clock: Clock
) {

    /**
     * Creates a notification
     *
     * @param notificationInput the notification to create
     * @return the created notification
     */
    suspend fun createNotification(notificationInput: NotificationInput): NotificationEntity {
        require(userRepository.existsById(notificationInput.userId)) {
            "User with id ${notificationInput.userId} does not exist"
        }
        val notification = NotificationEntity(
            title = notificationInput.title,
            body = notificationInput.body,
            dateSent = OffsetDateTime.now(clock),
            dateRead = null,
            userId = notificationInput.userId,
            id = null
        )
        return repository.save(notification)
    }

    /**
     * Updates a notification. Foreign notifications need the admin role (not employee);
     * the row is always written.
     *
     * @param input defines which notification to update and how
     * @param authorizedUser the user making the request
     * @return the updated notification
     */
    @Transactional
    suspend fun updateNotification(input: UpdateNotificationInput, authorizedUser: AuthorizedUser): NotificationEntity {
        val notification = repository.findByIdOrThrow(input.id)
        if (notification.userId != authorizedUser.id) {
            authorizedUser.checkIsAdmin()
        }
        if ((notification.dateRead != null) != input.isRead) {
            notification.dateRead = if (input.isRead) {
                OffsetDateTime.now(clock)
            } else {
                null
            }
        }
        return repository.save(notification)
    }

}
