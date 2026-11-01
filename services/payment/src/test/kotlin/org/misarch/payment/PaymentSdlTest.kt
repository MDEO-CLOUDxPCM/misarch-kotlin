package org.misarch.payment

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original payment service's (`reference/sdl/payment.graphql`).
 */
class PaymentSdlTest : SdlComparisonTest("payment")
