package org.misarch.address.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Query
import graphql.schema.DataFetchingEnvironment
import org.misarch.address.graphql.dataloader.AddressDataLoader
import org.misarch.address.graphql.model.Address
import org.misarch.address.graphql.model.UserAddress
import org.misarch.address.graphql.model.VendorAddress
import org.misarch.address.service.VendorAddressService
import org.misarch.common.graphql.authorizedUser
import org.misarch.common.graphql.load
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Defines GraphQL queries
 */
@Component
class Query(
    private val vendorAddressService: VendorAddressService
) : Query {

    @GraphQLDescription("Get a address by id")
    fun address(
        @GraphQLDescription("The id of the address")
        id: UUID,
        dfe: DataFetchingEnvironment
    ): CompletableFuture<Address> {
        return dfe.load(AddressDataLoader::class, id).thenApply {
            if (it is UserAddress) {
                val authorizedUser = dfe.authorizedUser
                if (it.userId != authorizedUser.id) {
                    authorizedUser.checkIsEmployee()
                }
            }
            it
        }
    }

    @GraphQLDescription("Get the current vendor address")
    suspend fun vendorAddress(): VendorAddress? {
        return vendorAddressService.findCurrentVendorAddress()?.toDTO() as VendorAddress?
    }

}
