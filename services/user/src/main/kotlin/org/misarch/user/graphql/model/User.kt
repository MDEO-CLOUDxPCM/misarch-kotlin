package org.misarch.user.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@GraphQLDescription("A user.")
@KeyDirective(fields = FieldSet("id"))
class User(
    id: UUID,
    @property:GraphQLDescription("The username of the user")
    val username: String,
    @property:GraphQLDescription("The name of the user")
    val name: Name,
    private val birthday: LocalDate?,
    private val gender: Gender?,
    private val dateJoined: OffsetDateTime,
) : Node(id) {

    @GraphQLDescription("The birthday of the user")
    fun birthday(dfe: DataFetchingEnvironment): LocalDate? {
        checkIsSameUserOrEmployee(dfe)
        return birthday
    }

    @GraphQLDescription("The gender of the user")
    fun gender(dfe: DataFetchingEnvironment): Gender? {
        checkIsSameUserOrEmployee(dfe)
        return gender
    }

    @GraphQLDescription("The date when the user joined")
    fun dateJoined(dfe: DataFetchingEnvironment): OffsetDateTime {
        checkIsSameUserOrEmployee(dfe)
        return dateJoined
    }

    /**
     * Checks that the user is the same as the authorized user or that the authorized user is an employee.
     *
     * @param dfe The DataFetchingEnvironment to get the authorized user from
     */
    private fun checkIsSameUserOrEmployee(dfe: DataFetchingEnvironment) {
        val authorizedUser = dfe.authorizedUser
        if (authorizedUser.id != id) {
            authorizedUser.checkIsEmployee()
        }
    }

}
