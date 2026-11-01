package org.misarch.media.service

import java.util.UUID

/**
 * A media file of the store: the object `<id>.<extension>`.
 *
 * @property key the object key
 */
data class MediaObject(val key: String) {

    /** The id of the media (the key without extension; fails for a foreign object name). */
    val id: UUID get() = UUID.fromString(key.substringBeforeLast('.'))
}
