package org.misarch.tax.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.tax.graphql.dataloader.TaxRateVersionDataLoader
import org.misarch.tax.graphql.model.TaxRateVersion
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [TaxRateVersion]s (no authorization, like the original).
 */
@Component
class TaxRateVersionResolver : FederatedTypePromiseResolver<TaxRateVersion> {
    override val typeName: String
        get() = TaxRateVersion::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<TaxRateVersion?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, TaxRateVersion?>(TaxRateVersionDataLoader::class.simpleName!!)
                .load(UUID.fromString(id))
        }
    }
}
