package org.misarch.tax.event

/**
 * Constants for tax event topics used in the application
 */
object TaxEvents {

    /**
     * Topic for tax rate creation events
     */
    const val TAX_RATE_CREATED = "tax/tax-rate/created"

    /**
     * Topic for tax rate version creation events
     */
    const val TAX_RATE_VERSION_CREATED = "tax/tax-rate-version/created"

    /**
     * Name of the pubsub component
     */
    const val PUBSUB_NAME = "pubsub"
}
