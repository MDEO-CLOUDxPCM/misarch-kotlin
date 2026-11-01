package org.misarch.`return`.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.Node
import org.misarch.common.graphql.load
import org.misarch.`return`.graphql.dataloader.ReturnDataLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("An order item.")
@KeyDirective(fields = FieldSet("id"))
class OrderItem(
    id: UUID,
    private val returnedWithId: UUID?
) : Node(id) {

    @GraphQLDescription("The return this order item was returned with.")
    fun returnedWith(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Return?> {
        if (returnedWithId == null) {
            return CompletableFuture.completedFuture(null)
        }
        return dfe.load(ReturnDataLoader::class, returnedWithId).thenApply<Return?> { it }
    }

}
