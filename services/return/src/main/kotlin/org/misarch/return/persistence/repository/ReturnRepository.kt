package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.ReturnEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.UUID

/**
 * Repository for [ReturnEntity]s
 */
interface ReturnRepository : CoroutineCrudRepository<ReturnEntity, UUID>
