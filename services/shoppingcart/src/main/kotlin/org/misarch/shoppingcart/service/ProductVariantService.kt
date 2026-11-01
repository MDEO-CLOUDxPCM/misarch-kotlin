package org.misarch.shoppingcart.service

import org.misarch.common.persistence.Condition
import org.misarch.common.persistence.ConditionQueries
import org.misarch.shoppingcart.event.model.ProductVariantDTO
import org.misarch.shoppingcart.persistence.model.ProductVariantEntity
import org.misarch.shoppingcart.persistence.repository.ProductVariantRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Service for [ProductVariantEntity]s
 */
@Service
class ProductVariantService(
    private val repository: ProductVariantRepository,
    private val conditionQueries: ConditionQueries,
) {

    /**
     * Registers a product variant (replica, from `catalog/product-variant/created`); idempotent
     *
     * @param productVariantDTO the product variant to register
     */
    suspend fun registerProductVariant(productVariantDTO: ProductVariantDTO) {
        repository.createProductVariant(productVariantDTO.id)
    }

    /**
     * Checks that all product variants exist (one query)
     *
     * @param productVariantIds the ids to check
     * @throws IllegalArgumentException naming the first unknown product variant
     */
    suspend fun validateProductVariantIds(productVariantIds: Collection<UUID>) {
        if (productVariantIds.isEmpty()) {
            return
        }
        val existing = conditionQueries.findAll<ProductVariantEntity>(Condition.inArray("t.id", productVariantIds))
            .map { it.id }.toSet()
        val unknown = productVariantIds.firstOrNull { it !in existing }
        require(unknown == null) { "Product variant with id $unknown does not exist" }
    }

}
