package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.NumericalCategoryCharacteristicDataLoader
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristic
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [NumericalCategoryCharacteristic]s.
 */
@Component
class NumericalCategoryCharacteristicResolver : DataLoaderEntityResolver<NumericalCategoryCharacteristic>(NumericalCategoryCharacteristic::class, NumericalCategoryCharacteristicDataLoader::class)
