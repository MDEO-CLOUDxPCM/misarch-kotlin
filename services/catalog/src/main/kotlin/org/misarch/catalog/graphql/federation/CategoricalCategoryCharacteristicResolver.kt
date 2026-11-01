package org.misarch.catalog.graphql.federation

import org.misarch.catalog.graphql.dataloader.CategoricalCategoryCharacteristicDataLoader
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.common.graphql.DataLoaderEntityResolver
import org.springframework.stereotype.Component

/**
 * Federated resolver for [CategoricalCategoryCharacteristic]s.
 */
@Component
class CategoricalCategoryCharacteristicResolver : DataLoaderEntityResolver<CategoricalCategoryCharacteristic>(CategoricalCategoryCharacteristic::class, CategoricalCategoryCharacteristicDataLoader::class)
