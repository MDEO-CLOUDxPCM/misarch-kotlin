package org.misarch.discount.persistence.repository

import org.misarch.discount.persistence.model.ProductEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for [ProductEntity]s
 */
@Repository
interface ProductRepository : CoroutineCrudRepository<ProductEntity, UUID> {

    /**
     * Creates a product (replica, fed by an event). A duplicate id fails (primary key), like in the original.
     *
     * @param id the id of the product
     */
    @Modifying
    @Query("INSERT INTO ProductEntity (id) VALUES (:id)")
    suspend fun createProduct(
        @Param("id") id: UUID
    )

}
