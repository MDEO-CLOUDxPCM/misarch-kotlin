package org.misarch.inventory.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.annotations.GraphQLIgnore
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.GraphQLDefaultValue
import org.misarch.inventory.graphql.ConnectionArgs
import org.misarch.inventory.graphql.input.ProductItemOfProductVariantFilter
import org.misarch.inventory.graphql.input.ProductItemOrder
import org.misarch.common.nest.JsError
import org.misarch.common.nest.RolesGuard
import org.misarch.common.nest.Uuid
import org.misarch.inventory.nest.queryKeys
import org.misarch.inventory.persistence.FilterValue
import org.misarch.inventory.persistence.ProductItemRow
import org.misarch.inventory.service.InventoryService
import org.misarch.inventory.service.ProductItemStatuses
import org.springframework.beans.factory.annotation.Autowired

@GraphQLDescription("A product Item of a product variant")
@KeyDirective(fields = FieldSet("id"))
class ProductItem(internal val row: ProductItemRow) {

    @GraphQLDescription("The uuid identifier of the product item")
    fun id(): Uuid = Uuid(row.id)

    @GraphQLDescription("The corresponding product variant")
    fun productVariant(): ProductVariant = ProductVariant(row.productVariant)

    @GraphQLDescription("Describes the inventory status of the product item")
    fun inventoryStatus(): ProductItemStatus = ProductItemStatus.valueOf(row.inventoryStatus)

    @GraphQLDescription("Reference to the order that reserved the product item")
    fun orderId(): Uuid? = row.orderId?.let { Uuid(it) }
}

/**
 * The foreign type ProductVariant. [rawId] is the representation's / item's variant id (null for phantom items).
 */
@GraphQLDescription("Foreign type ProductVariant")
@KeyDirective(fields = FieldSet("id"))
class ProductVariant(internal val rawId: String?) {

    @GraphQLDescription("The uuid identifier of the product variant")
    fun id(): Uuid = rawId?.let { Uuid(it) }
        ?: throw JsError("Cannot return null for non-nullable field ProductVariant.id.")

    @GraphQLDescription("A product item connection for referenced product items in stock")
    suspend fun productItems(
        @GraphQLDescription("Number of items to skip")
        @GraphQLDefaultValue("0")
        skip: Int? = 0,
        @GraphQLDescription("Number of items to return")
        @GraphQLDefaultValue("2147483647")
        first: Int? = Int.MAX_VALUE,
        @GraphQLDescription("Ordering")
        orderBy: ProductItemOrder? = null,
        @GraphQLDescription("Filtering")
        filter: ProductItemOfProductVariantFilter? = null,
        @GraphQLIgnore @Autowired inventoryService: InventoryService,
        dfe: DataFetchingEnvironment,
    ): ProductItemConnection? {
        RolesGuard.check(dfe, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
        val args = ConnectionArgs(dfe)
        args.validate()
        // the filter argument is replaced by {productVariant: id}; buildQuery maps it to the non-existent field
        // `status`, so nodes are always empty for a set id (quirk); the count uses the real field
        val variant = if (rawId == null) FilterValue.Null else FilterValue.Value(rawId)
        return ProductItemConnection.build(
            dfe.queryKeys(), args,
            nodes = { if (!rawId.isNullOrEmpty()) emptyList() else inventoryService.findAll(args.ascending, args.first!!, args.skip!!) },
            count = { inventoryService.count(variant, FilterValue.Absent) },
        )
    }

    @GraphQLDescription("The number of product items in stock")
    suspend fun inventoryCount(@GraphQLIgnore @Autowired inventoryService: InventoryService): Int {
        val variant = if (rawId == null) FilterValue.Null else FilterValue.Value(rawId)
        return inventoryService.count(variant, FilterValue.Value(ProductItemStatuses.IN_STORAGE)).toInt()
    }
}

/**
 * `ProductItemConnection` built like the original's `buildConnection`: `nodes` only if selected,
 * `totalCount`/`hasNextPage` only if one of them is selected (non-null violation otherwise, as in the original).
 */
@GraphQLDescription("A connection of product items")
class ProductItemConnection private constructor(
    private val nodeList: List<ProductItem>?,
    private val total: Long?,
    private val nextPage: Boolean?,
) {
    @GraphQLDescription("The resulting items")
    fun nodes(): List<ProductItem>? = nodeList

    @GraphQLDescription("The total amount of items in this connection")
    fun totalCount(): Int = total?.toInt()
        ?: throw JsError("Cannot return null for non-nullable field ProductItemConnection.totalCount.")

    @GraphQLDescription("Whether this connection has a next page")
    fun hasNextPage(): Boolean = nextPage
        ?: throw JsError("Cannot return null for non-nullable field ProductItemConnection.hasNextPage.")

    companion object {
        suspend fun build(
            keys: List<String>,
            args: ConnectionArgs,
            nodes: suspend () -> List<ProductItemRow>,
            count: suspend () -> Long,
        ): ProductItemConnection {
            val nodeList = if ("nodes" in keys) nodes().map { ProductItem(it) } else null
            var total: Long? = null
            var nextPage: Boolean? = null
            if ("totalCount" in keys || "hasNextPage" in keys) {
                total = count()
                nextPage = (args.skip ?: 0) + (args.first ?: 0) < total
            }
            return ProductItemConnection(nodeList, total, nextPage)
        }
    }
}
