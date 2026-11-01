package org.misarch.payment

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * The federation SDL equals the reference SDL (`reference/sdl/payment.graphql`).
 * The provider URL is required at startup, so the test sets an unreachable one.
 */
@TestPropertySource(properties = ["misarch.payment.provider.simulation-url=http://127.0.0.1:1"])
class PaymentSdlTest : SdlComparisonTest("payment")
