package org.misarch.catalog.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * Base class of the types implementing the `Node` interface (global object identification by UUID)
 */
@GraphQLDescription("An object with an ID.")
abstract class Node(
    @property:GraphQLDescription("The ID of the node.")
    val id: UUID
)
