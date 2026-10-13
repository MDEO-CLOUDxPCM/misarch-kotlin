package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.CategoryCharacteristicDataLoader
import org.misarch.common.graphql.dataLoader
import java.util.*
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A value for a CategoryCharacteristic.")
abstract class CategoryCharacteristicValue(
    private val characteristicId: UUID
) {

    @GraphQLDescription("The associated CategoryCharacteristic this is a value of.")
    fun characteristic(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<CategoryCharacteristic> {
        return dfe.dataLoader<UUID, CategoryCharacteristic>(CategoryCharacteristicDataLoader::class.simpleName!!)
            .load(characteristicId, dfe)
    }

}
