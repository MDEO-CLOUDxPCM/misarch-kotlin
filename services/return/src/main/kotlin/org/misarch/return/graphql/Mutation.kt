package org.misarch.`return`.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.`return`.graphql.input.CreateReturnInput
import org.misarch.`return`.graphql.model.Return
import org.misarch.`return`.service.ReturnService
import org.misarch.common.graphql.authorizedUser
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 *
 * @property returnService used for Return-related mutations
 */
@Component
class Mutation(
    private val returnService: ReturnService
) : Mutation {

    @GraphQLDescription("Create a new return")
    suspend fun createReturn(
        @GraphQLDescription("Input for the createReturn mutation")
        input: CreateReturnInput,
        dfe: DataFetchingEnvironment
    ): Return {
        return returnService.createReturn(input, dfe.authorizedUser).toDTO()
    }

}
