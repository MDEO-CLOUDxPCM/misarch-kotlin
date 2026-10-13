package org.misarch.discount.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.discount.graphql.dataloader.CouponDataLoader
import org.misarch.discount.graphql.model.Coupon
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Coupon]s.
 */
@Component
class CouponResolver : FederatedTypePromiseResolver<Coupon> {
    override val typeName: String
        get() = Coupon::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Coupon?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, Coupon>(CouponDataLoader::class.simpleName!!)
                .load(UUID.fromString(id)).thenApply { it }
        }
    }
}
