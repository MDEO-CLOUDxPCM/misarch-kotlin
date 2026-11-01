package org.misarch.catalog.service

import org.misarch.catalog.event.model.CreateMediaDTO
import org.misarch.catalog.persistence.model.MediaEntity
import org.misarch.catalog.persistence.repository.MediaRepository
import org.springframework.stereotype.Service

/**
 * Service for [MediaEntity]s
 */
@Service
class MediaService(
    private val repository: MediaRepository
) {

    /**
     * Registers a Media (replica, from `media/media/created`). Idempotent, so a redelivered event is acknowledged.
     *
     * @param mediaDTO the Media to register
     */
    suspend fun registerMedia(mediaDTO: CreateMediaDTO) {
        repository.createMedia(mediaDTO.id)
    }

}
