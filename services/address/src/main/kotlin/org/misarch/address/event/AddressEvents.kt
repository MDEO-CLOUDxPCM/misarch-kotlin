package org.misarch.address.event

/**
 * Constants for address event topics used in the application
 */
object AddressEvents {
    /**
     * Topic for user creation events (a user has been created)
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for user address creation events (a user address has been created)
     */
    const val USER_ADDRESS_CREATED = "address/user-address/created"

    /**
     * Topic for user address archive events (a user address has been archived)
     */
    const val USER_ADDRESS_ARCHIVED = "address/user-address/archived"

    /**
     * Topic for vendor address creation events (a vendor address has been created)
     */
    const val VENDOR_ADDRESS_CREATED = "address/vendor-address/created"
}
