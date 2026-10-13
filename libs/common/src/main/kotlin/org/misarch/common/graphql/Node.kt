package org.misarch.common.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

/**
 * The `Node` interface shared by all MiSArch subgraphs (ADR: global object identification by UUID).
 * A service whose original `Node` differs (description, fields) defines its own `Node` type instead.
 */
@GraphQLDescription("An object with an ID.")
abstract class Node(
    @property:GraphQLDescription("The ID of the node.")
    val id: UUID
)
