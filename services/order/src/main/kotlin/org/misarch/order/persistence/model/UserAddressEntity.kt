package org.misarch.order.persistence.model

import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

/**
 * Entity for the (not archived) addresses of a user (replica, fed by `address/user-address/created` and `archived`)
 *
 * @property key the user and the address
 */
@Table
class UserAddressEntity(
    @Id
    val key: UserAddressKey
) : Persistable<UserAddressKey> {

    override fun getId(): UserAddressKey = key

    /** Rows are only inserted or deleted, never updated. */
    override fun isNew(): Boolean = true
}

/**
 * Primary key of [UserAddressEntity].
 *
 * @property userId the id of the user
 * @property id the id of the address
 */
data class UserAddressKey(val userId: UUID, val id: UUID)
