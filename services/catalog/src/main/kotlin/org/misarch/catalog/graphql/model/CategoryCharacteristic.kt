package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.CategoryDataLoader
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.dataLoader
import java.util.*
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A characteristic of a Category.")
abstract class CategoryCharacteristic(
    id: UUID,
    @property:GraphQLDescription("The name of the CategoryCharacteristic.")
    val name: String,
    @property:GraphQLDescription("The description of the CategoryCharacteristic.")
    val description: String, private val categoryId: UUID
) : Node(id) {

    @GraphQLDescription("The Category this item belongs to.")
    fun category(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Category> {
        return dfe.dataLoader<UUID, Category>(CategoryDataLoader::class.simpleName!!)
            .load(categoryId, dfe)
    }

}
