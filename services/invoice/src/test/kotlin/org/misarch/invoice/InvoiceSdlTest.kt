package org.misarch.invoice

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original invoice service's (`reference/sdl/invoice.graphql`).
 */
class InvoiceSdlTest : SdlComparisonTest("invoice") {
    override val graphqlPath: String = "/"
}
