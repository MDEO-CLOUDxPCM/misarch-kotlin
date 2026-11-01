package org.misarch.`return`.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.persistence.model.ReturnEntity
import org.misarch.`return`.persistence.repository.ReturnRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [Return]s
 *
 * @param repository repository for [ReturnEntity]s
 */
@Component
class ReturnDataLoader(
    repository: ReturnRepository
) : IdDataLoader<Return, ReturnEntity>(repository) {

    /** An unknown id fails only its own key (`… : null`), not the whole batch (docs/fixes/return.md). */
}
