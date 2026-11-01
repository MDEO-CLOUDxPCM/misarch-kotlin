package org.misarch.tax.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.tax.graphql.dataloader.TaxRateDataLoader
import org.misarch.tax.graphql.model.TaxRate
import org.springframework.stereotype.Component

/**
 * Federated resolver for [TaxRate]s (no authorization).
 */
@Component
class TaxRateResolver : DataLoaderEntityResolver<TaxRate>(TaxRate::class, TaxRateDataLoader::class)
