package org.misarch.`return`.persistence.repository

import org.misarch.`return`.persistence.model.ReturnEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Repository for [ReturnEntity]s
 */
@Repository
interface ReturnRepository : CoroutineCrudRepository<ReturnEntity, UUID>
