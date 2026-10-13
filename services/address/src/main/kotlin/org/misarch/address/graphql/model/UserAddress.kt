package org.misarch.address.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.address.graphql.dataloader.UserDataLoader
import org.misarch.common.graphql.dataLoader
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletableFuture

@GraphQLDescription("A address associated with a user.")
@KeyDirective(fields = FieldSet("id"))
class UserAddress(
    id: UUID,
    name: Name?,
    street1: String,
    street2: String,
    city: String,
    postalCode: String,
    country: String,
    companyName: String?,
    internal val userId: UUID,
    @property:GraphQLDescription("If this address is archived, the datetime it was archived.")
    val archivedAt: OffsetDateTime?
) : Address(id, name, street1, street2, city, postalCode, country, companyName) {

    @GraphQLDescription("If true, this address is archived and can no longer be used.")
    val isArchived: Boolean
        get() = archivedAt != null

    @GraphQLDescription("The user this address belongs to.")
    fun user(
        dfe: DataFetchingEnvironment
    ): CompletableFuture<User> {
        return dfe.dataLoader<UUID, User>(UserDataLoader::class.simpleName!!).load(userId, dfe)
    }

}
