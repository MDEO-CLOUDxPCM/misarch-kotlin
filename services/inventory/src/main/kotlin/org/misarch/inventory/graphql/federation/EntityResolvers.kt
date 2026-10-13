package org.misarch.inventory.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.inventory.graphql.model.ProductItem
import org.misarch.inventory.graphql.model.ProductVariant
import org.misarch.common.nest.EntityErrors
import org.misarch.common.nest.RolesGuard
import org.misarch.inventory.service.InventoryService
import org.springframework.stereotype.Component

/** `@ResolveReference` of `ProductItem`: guarded (employee/admin), `findById`. */
@Component
class ProductItemResolver(private val inventoryService: InventoryService) : FederatedTypeSuspendResolver<ProductItem> {
    override val typeName: String = "ProductItem"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): ProductItem? =
        try {
            RolesGuard.check(environment, RolesGuard.EMPLOYEE, RolesGuard.ADMIN)
            ProductItem(inventoryService.findById(representation["id"].toString()))
        } catch (e: Throwable) {
            EntityErrors.record(environment, representation, e)
            null
        }
}

/** `ProductVariant` has no reference resolver in the original: the representation itself is the parent. */
@Component
class ProductVariantResolver : FederatedTypeSuspendResolver<ProductVariant> {
    override val typeName: String = "ProductVariant"

    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): ProductVariant =
        ProductVariant(representation["id"]?.toString())
}
