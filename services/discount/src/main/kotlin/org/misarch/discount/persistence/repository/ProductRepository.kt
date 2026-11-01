package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.util.*

/**
 * Repository for [ProductEntity]s
 */
interface ProductRepository : CoroutineCrudRepository<ProductEntity, UUID> {

    /**
     * Creates a product (replica, fed by an event); a redelivered event is a no-op.
     *
     * @param id the id of the product
     */
    @Modifying
    @Query("INSERT INTO ProductEntity (id) VALUES (:id) ON CONFLICT (id) DO NOTHING")
    suspend fun createProduct(
        id: UUID
    )

}
