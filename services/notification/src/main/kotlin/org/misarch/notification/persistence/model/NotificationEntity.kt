package org.misarch.notification.persistence.model

import org.misarch.common.persistence.BaseEntity
import org.misarch.notification.graphql.model.Notification
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Entity for notifications (table `NotificationEntity`)
 *
 * @property title title of the notification
 * @property body body of the notification
 * @property dateSent date the notification was sent
 * @property dateRead date the notification was read
 * @property userId id of the user the notification was sent to
 * @property id unique identifier of the notification
 */
@Table
class NotificationEntity(
    val title: String,
    val body: String,
    val dateSent: OffsetDateTime,
    var dateRead: OffsetDateTime?,
    val userId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<Notification> {

    override fun toDTO(): Notification {
        return Notification(
            id = id!!,
            title = title,
            body = body,
            dateSent = dateSent,
            dateRead = dateRead,
            userId = userId
        )
    }

}
