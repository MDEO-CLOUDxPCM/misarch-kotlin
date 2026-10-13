package org.misarch.`return`.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for the createReturn mutation")
class CreateReturnInput(
    @property:GraphQLDescription("The order items to return, must belong to the same order and must not be already returned.")
    val orderItemIds: List<UUID>,
    @property:GraphQLDescription("The reason for the return.")
    val reason: String
)
