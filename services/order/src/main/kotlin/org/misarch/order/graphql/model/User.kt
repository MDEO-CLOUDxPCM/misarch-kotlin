package org.misarch.order.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.order.auth.authorizeUser
import org.misarch.order.graphql.input.OrderOrderInput
import org.misarch.order.graphql.model.connection.OrderConnection
import org.misarch.order.graphql.scalar.unsignedArgument
import org.misarch.order.persistence.OrderStore
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

@GraphQLDescription("Type of a user owning orders.")
@KeyDirective(fields = FieldSet("id"))
class User(
    @property:GraphQLDescription("UUID of user.")
    val id: UUID,
) {

    /**
     * The original pages with `mongodb-cursor-pagination` 0.3.2 (docs/specs/order.md §5.5): page size `first` (default
     * **25**), `hasNextPage` = a further order exists; tiebreak `_id: -1` unless sorting by id. A connection over the
     * user's `orders` rows: `nodes`, `totalCount` and `hasNextPage` are separate statements, each run only when selected.
     * FIXED (docs/fixes/order.md): with `skip > 0` the original computed `hasNextPage = fetched + skip < totalCount` and
     * dropped the extra row only then, so a page could hold `first + 1` nodes with `hasNextPage = false`.
     */
    @GraphQLDescription("Retrieves orders of user.")
    suspend fun orders(
        @GraphQLDescription("Describes that the `first` N orders should be retrieved.")
        first: Int? = null,
        @GraphQLDescription("Describes how many orders should be skipped at the beginning.")
        skip: Int? = null,
        @GraphQLDescription("Specifies the order in which orders are retrieved.")
        orderBy: OrderOrderInput? = null,
        @GraphQLIgnore
        @Autowired
        store: OrderStore,
        dfe: DataFetchingEnvironment,
    ): OrderConnection {
        val pageSize = unsignedArgument(first) ?: DEFAULT_LIMIT
        val definitelySkip = unsignedArgument(skip) ?: 0
        dfe.authorizeUser(id)
        val field = orderBy?.field ?: OrderOrderField.ID
        val direction = orderBy?.direction ?: OrderDirection.ASC
        return OrderConnection(store.ordersOfUser(id, field.sql(direction), pageSize, definitelySkip))
    }

    companion object {
        /** `mongodb-cursor-pagination`'s `DEFAULT_LIMIT`. */
        const val DEFAULT_LIMIT = 25
    }
}
