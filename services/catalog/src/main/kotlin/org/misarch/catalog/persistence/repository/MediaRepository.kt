package org.misarch.catalog.persistence.repository

import org.misarch.catalog.persistence.model.MediaEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [MediaEntity]s
 */
interface MediaRepository : CoroutineCrudRepository<MediaEntity, UUID> {

    /** Idempotent: a redelivered creation event is a no-op (docs/fixes/catalog.md). */
    @Modifying
    @Query("INSERT INTO MediaEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createMedia(
        id: UUID
    )

}
