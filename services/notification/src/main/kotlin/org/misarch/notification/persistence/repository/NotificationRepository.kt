package org.misarch.notification.persistence.repository

import org.misarch.notification.persistence.model.NotificationEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [NotificationEntity]s
 */
@Repository
interface NotificationRepository : CoroutineCrudRepository<NotificationEntity, UUID>
