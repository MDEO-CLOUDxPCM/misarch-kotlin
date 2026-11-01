package org.misarch.media.graphql.federation

import org.misarch.common.graphql.ReferenceEntityResolver
import org.misarch.media.graphql.model.Media
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Media] (no store access: the path is resolved when selected)
 */
@Component
class MediaResolver : ReferenceEntityResolver<Media>(Media::class, ::Media)
