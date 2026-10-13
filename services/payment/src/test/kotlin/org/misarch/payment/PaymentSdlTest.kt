package org.misarch.payment

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.context.annotation.Import

/**
 * The federation SDL equals the original payment service's (`reference/sdl/payment.graphql`).
 *
 * The recording terminator keeps this context (no database) alive when the 15-minute payment crons fire during the
 * test run (their DB error would otherwise halt the test JVM like the original Node process).
 */
@Import(PaymentTestConfiguration::class)
class PaymentSdlTest : SdlComparisonTest("payment")
