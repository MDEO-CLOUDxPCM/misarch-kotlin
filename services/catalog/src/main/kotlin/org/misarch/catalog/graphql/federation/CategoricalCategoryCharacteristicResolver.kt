package org.misarch.catalog.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.CategoricalCategoryCharacteristicDataLoader
import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.common.graphql.dataLoader
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [CategoricalCategoryCharacteristic]s.
 */
@Component
class CategoricalCategoryCharacteristicResolver : FederatedTypePromiseResolver<CategoricalCategoryCharacteristic> {
    override val typeName: String
        get() = CategoricalCategoryCharacteristic::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<CategoricalCategoryCharacteristic?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, CategoricalCategoryCharacteristic?>(CategoricalCategoryCharacteristicDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
