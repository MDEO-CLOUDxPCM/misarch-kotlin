package org.misarch.discount.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.load
import org.misarch.discount.graphql.dataloader.DiscountDataLoader
import org.misarch.discount.graphql.dataloader.UserDataLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("Discount usage by a user.")
@KeyDirective(fields = FieldSet("id"))
class DiscountUsage(
    id: UUID,
    @property:GraphQLDescription("The amount of items the user has used this discount for.")
    val usages: Int,
    private val discountId: UUID,
    private val userId: UUID
) : Node(id) {

    @GraphQLDescription("The user using the discount.")
    fun user(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<User> {
        return dfe.load(UserDataLoader::class, userId)
    }

    @GraphQLDescription("The used discount.")
    fun discount(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Discount> {
        return dfe.load(DiscountDataLoader::class, discountId)
    }

}
