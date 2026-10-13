package org.misarch.address.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.address.graphql.input.ArchiveUserAddressInput
import org.misarch.address.graphql.input.CreateUserAddressInput
import org.misarch.address.graphql.input.CreateVendorAddressInput
import org.misarch.address.graphql.model.UserAddress
import org.misarch.address.graphql.model.VendorAddress
import org.misarch.address.service.UserAddressService
import org.misarch.address.service.VendorAddressService
import org.misarch.common.graphql.authorizedUser
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Defines GraphQL mutations
 *
 * @property userAddressService service used to create and update user addresses
 * @property vendorAddressService service used to create vendor addresses
 */
@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
class Mutation(
    private val userAddressService: UserAddressService,
    private val vendorAddressService: VendorAddressService
) : Mutation {

    @GraphQLDescription("Create a new user address")
    suspend fun createUserAddress(
        @GraphQLDescription("Input for the createUserAddress mutation")
        input: CreateUserAddressInput,
        dfe: DataFetchingEnvironment
    ): UserAddress {
        val authorizedUser = dfe.authorizedUser
        if (input.userId != authorizedUser.id) {
            authorizedUser.checkIsEmployee()
        }
        val userAddress = userAddressService.createUserAddress(input)
        return userAddress.toDTO() as UserAddress
    }

    @GraphQLDescription("Create a new vendor address")
    suspend fun createVendorAddress(
        @GraphQLDescription("Input for the createVendorAddress mutation")
        input: CreateVendorAddressInput,
        dfe: DataFetchingEnvironment
    ): VendorAddress {
        dfe.authorizedUser.checkIsEmployee()
        val vendorAddress = vendorAddressService.createVendorAddress(input)
        return vendorAddress.toDTO() as VendorAddress
    }

    @GraphQLDescription("Archive a user address")
    suspend fun archiveUserAddress(
        @GraphQLDescription("Input for the archiveUserAddress mutation")
        input: ArchiveUserAddressInput,
        dfe: DataFetchingEnvironment
    ): UserAddress {
        val userAddress = userAddressService.archiveUserAddress(input, dfe.authorizedUser)
        return userAddress.toDTO() as UserAddress
    }
}
