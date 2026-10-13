package org.misarch.notification.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.notification.graphql.model.Notification
import org.misarch.notification.persistence.model.NotificationEntity
import org.misarch.notification.persistence.repository.NotificationRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Notification]s
 *
 * @param repository repository for [NotificationEntity]s
 */
@Component
class NotificationDataLoader(
    repository: NotificationRepository
) : IdDataLoader<Notification, NotificationEntity>(repository)
