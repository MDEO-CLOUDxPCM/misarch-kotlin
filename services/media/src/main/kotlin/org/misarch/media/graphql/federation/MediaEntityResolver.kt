package org.misarch.media.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustEntitiesDataFetcher
import org.misarch.common.rust.RustUuid
import org.misarch.media.graphql.model.Media
import org.springframework.stereotype.Component

/** `media_entity_resolver`: any parsable id, no existence check, no I/O. */
@Component
class MediaEntityResolver : FederatedTypeSuspendResolver<Media> {
    override val typeName = "Media"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Media =
        Media((representation["id"] as? String)?.let(RustUuid::parseOrNull) ?: throw RustEntitiesDataFetcher.entityNotFound())
}
