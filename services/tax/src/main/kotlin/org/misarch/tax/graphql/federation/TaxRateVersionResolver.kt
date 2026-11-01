package org.misarch.tax.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.tax.graphql.dataloader.TaxRateVersionDataLoader
import org.misarch.tax.graphql.model.TaxRateVersion
import org.springframework.stereotype.Component

/**
 * Federated resolver for [TaxRateVersion]s (no authorization, like the original).
 */
@Component
class TaxRateVersionResolver : DataLoaderEntityResolver<TaxRateVersion>(TaxRateVersion::class, TaxRateVersionDataLoader::class)
