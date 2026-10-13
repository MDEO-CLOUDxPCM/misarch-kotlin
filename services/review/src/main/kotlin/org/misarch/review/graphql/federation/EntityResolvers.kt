package org.misarch.review.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypeSuspendResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.rust.RustEntitiesDataFetcher
import org.misarch.common.rust.RustUuid
import org.misarch.review.graphql.model.Product
import org.misarch.review.graphql.model.ProductVariant
import org.misarch.review.graphql.model.User
import org.misarch.review.service.ReviewService
import org.springframework.stereotype.Component
import java.util.UUID

/** The `id` key of a representation; missing/unparsable → `Entity not found.` (async-graphql key getter). */
internal fun Map<String, Any>.idKey(): UUID =
    (this["id"] as? String)?.let(RustUuid::parseOrNull) ?: throw RustEntitiesDataFetcher.entityNotFound()

/** `user_entity_resolver`: replica lookup, null if missing. */
@Component
class UserEntityResolver(private val reviewService: ReviewService) : FederatedTypeSuspendResolver<User> {
    override val typeName = "User"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): User? {
        val id = representation.idKey()
        return if (reviewService.userExists(id)) User(id) else null
    }
}

/** `product_entity_resolver`: replica lookup, null if missing. */
@Component
class ProductEntityResolver(private val reviewService: ReviewService) : FederatedTypeSuspendResolver<Product> {
    override val typeName = "Product"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): Product? {
        val id = representation.idKey()
        return if (reviewService.productExists(id)) Product(id) else null
    }
}

/** `product_variant_entity_resolver`: replica lookup, null if missing (the replica is filled since the D1 fix). */
@Component
class ProductVariantEntityResolver(private val reviewService: ReviewService) :
    FederatedTypeSuspendResolver<ProductVariant> {
    override val typeName = "ProductVariant"
    override suspend fun resolve(environment: DataFetchingEnvironment, representation: Map<String, Any>): ProductVariant? {
        val id = representation.idKey()
        return if (reviewService.productVariantExists(id)) ProductVariant(id) else null
    }
}
