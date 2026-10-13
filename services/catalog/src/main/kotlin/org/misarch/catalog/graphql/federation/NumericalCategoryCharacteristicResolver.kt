package org.misarch.catalog.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.catalog.graphql.dataloader.NumericalCategoryCharacteristicDataLoader
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristic
import org.misarch.common.graphql.dataLoader
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [NumericalCategoryCharacteristic]s.
 */
@Component
class NumericalCategoryCharacteristicResolver : FederatedTypePromiseResolver<NumericalCategoryCharacteristic> {
    override val typeName: String
        get() = NumericalCategoryCharacteristic::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<NumericalCategoryCharacteristic?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, NumericalCategoryCharacteristic?>(NumericalCategoryCharacteristicDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
