package org.misarch.`return`.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.persistence.model.ReturnEntity
import org.misarch.`return`.persistence.repository.ReturnRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Return]s. An unknown id fails only its own key, not the whole batch.
 */
@Component
class ReturnDataLoader(
    repository: ReturnRepository
) : IdDataLoader<Return, ReturnEntity>(repository) {
}
