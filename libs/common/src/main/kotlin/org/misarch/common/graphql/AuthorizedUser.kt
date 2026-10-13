package org.misarch.common.graphql

import java.util.UUID

/**
 * The user on whose behalf a request is executed. The gateway validates the JWT and passes the user to every
 * subgraph as JSON in the `Authorized-User` HTTP header: `{"id": "<uuid>", "roles": ["buyer", ...]}`.
 * Roles used by MiSArch: `admin`, `employee`, `buyer`.
 *
 * @param id unique identifier of the user
 * @param roles list of roles assigned to the user
 */
data class AuthorizedUser(
    val id: UUID,
    val roles: List<String>
) {

    /** True if the user is an employee or admin. */
    val isEmployee: Boolean
        get() = roles.contains("employee") || isAdmin

    /** True if the user is an admin. */
    val isAdmin: Boolean
        get() = roles.contains("admin")

    /**
     * Check if the user is an admin and throw an exception if not.
     *
     * @throws IllegalStateException if the user is not an admin
     */
    fun checkIsAdmin() {
        if (!isAdmin) {
            throw IllegalStateException("Unauthorized access: $id is not an admin")
        }
    }

    /**
     * Check if the user is an employee (or admin) and throw an exception if not.
     *
     * @throws IllegalStateException if the user is not an employee
     */
    fun checkIsEmployee() {
        if (!isEmployee) {
            throw IllegalStateException("Unauthorized access: $id is not an employee or admin")
        }
    }

    companion object {
        /** Name of the HTTP header set by the gateway. */
        const val HEADER_NAME = "Authorized-User"
    }
}
