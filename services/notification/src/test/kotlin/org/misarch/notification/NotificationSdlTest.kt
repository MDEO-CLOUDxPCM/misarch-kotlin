package org.misarch.notification

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original notification service's (`reference/sdl/notification.graphql`).
 */
class NotificationSdlTest : SdlComparisonTest("notification")
