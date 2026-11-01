package org.misarch.catalog.persistence.model

import org.misarch.catalog.graphql.model.Media
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table
class MediaEntity(
    @Id
    override val id: UUID
) : BaseEntity<Media> {

    override fun toDTO(): Media {
        return Media(id)
    }
}