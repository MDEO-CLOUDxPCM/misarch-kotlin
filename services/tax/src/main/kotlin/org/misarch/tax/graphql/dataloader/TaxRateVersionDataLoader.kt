package org.misarch.tax.graphql.dataloader

import org.misarch.common.graphql.dataloader.IdDataLoader
import org.misarch.tax.graphql.model.TaxRateVersion
import org.misarch.tax.persistence.model.TaxRateVersionEntity
import org.misarch.tax.persistence.repository.TaxRateVersionRepository
import org.springframework.stereotype.Component

/**
 * Data loader for [TaxRateVersion]s
 *
 * @param repository repository for [TaxRateVersionEntity]s
 */
@Component
class TaxRateVersionDataLoader(
    repository: TaxRateVersionRepository
) : IdDataLoader<TaxRateVersion, TaxRateVersionEntity>(repository) {

    /** An unknown id fails only its own key (`… : null`), not the whole batch (docs/fixes/tax.md). */
    override val failMissingKeysIndividually: Boolean get() = true
}
