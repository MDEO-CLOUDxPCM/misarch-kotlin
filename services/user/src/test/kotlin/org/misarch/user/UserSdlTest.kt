package org.misarch.user

import org.misarch.common.testing.SdlComparisonTest

/**
 * The federation SDL equals the original user service's (`reference/sdl/user.graphql`).
 */
class UserSdlTest : SdlComparisonTest("user")
