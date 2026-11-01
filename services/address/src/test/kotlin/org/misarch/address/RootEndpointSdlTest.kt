package org.misarch.address

import org.misarch.common.testing.SdlComparisonTest
import org.springframework.test.context.TestPropertySource

/**
 * `graphql.endpoint: /` (the services the gateway reaches through Dapr's invoke API) serves the same schema at the
 * root path.
 */
@TestPropertySource(properties = ["graphql.endpoint=/"])
class RootEndpointSdlTest : SdlComparisonTest("address") {
    override val graphqlPath: String = "/"
}
