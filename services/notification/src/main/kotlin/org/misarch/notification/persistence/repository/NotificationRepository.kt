package org.misarch.notification.persistence.repository

import org.misarch.notification.persistence.model.NotificationEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [NotificationEntity]s
 */
interface NotificationRepository : CoroutineCrudRepository<NotificationEntity, UUID>
