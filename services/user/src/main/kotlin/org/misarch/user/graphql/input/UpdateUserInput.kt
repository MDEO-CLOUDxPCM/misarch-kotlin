package org.misarch.user.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.execution.OptionalInput
import org.misarch.user.graphql.model.Gender
import java.time.LocalDate
import java.util.UUID

@GraphQLDescription("Input for the updateUser mutation")
class UpdateUserInput(
    @property:GraphQLDescription("The id of the user to update")
    val id: UUID,
    @property:GraphQLDescription("The new first name of the user")
    val firstName: String?,
    @property:GraphQLDescription("The new last name of the user")
    val lastName: String?,
    @property:GraphQLDescription("The new birthday of the user")
    val birthday: OptionalInput<LocalDate?>,
    @property:GraphQLDescription("The new gender of the user")
    val gender: OptionalInput<Gender?>
)
