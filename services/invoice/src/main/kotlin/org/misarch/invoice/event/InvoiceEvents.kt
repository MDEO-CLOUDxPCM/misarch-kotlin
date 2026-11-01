package org.misarch.invoice.event

/**
 * Constants for invoice event topics used in the application
 */
object InvoiceEvents {
    /**
     * Topic for invoice creation events
     */
    const val INVOICE_CREATED = "invoice/invoice/created"

    /**
     * Topic for successful order validations (an invoice is created for the order)
     */
    const val ORDER_VALIDATION_SUCCEEDED = "discount/order/validation-succeeded"

    /**
     * Topic for vendor address creation events
     */
    const val VENDOR_ADDRESS_CREATED = "address/vendor-address/created"

    /**
     * Topic for user creation events
     */
    const val USER_CREATED = "user/user/created"

    /**
     * Topic for user address creation events
     */
    const val USER_ADDRESS_CREATED = "address/user-address/created"

    /**
     * Topic for user address archive events
     */
    const val USER_ADDRESS_ARCHIVED = "address/user-address/archived"
}
