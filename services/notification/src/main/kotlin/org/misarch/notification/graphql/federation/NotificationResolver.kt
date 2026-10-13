package org.misarch.notification.graphql.federation

import com.expediagroup.graphql.generator.federation.execution.FederatedTypePromiseResolver
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.dataLoader
import org.misarch.notification.graphql.dataloader.NotificationDataLoader
import org.misarch.notification.graphql.model.Notification
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Federated resolver for [Notification]s (no authorization check, like in the original).
 */
@Component
class NotificationResolver : FederatedTypePromiseResolver<Notification> {
    override val typeName: String
        get() = Notification::class.simpleName!!

    override fun resolve(
        environment: DataFetchingEnvironment, representation: Map<String, Any>
    ): CompletableFuture<Notification?> {
        val id = representation["id"] as String?
        return if (id == null) {
            CompletableFuture.completedFuture(null)
        } else {
            environment.dataLoader<UUID, Notification>(NotificationDataLoader::class.simpleName!!)
                .load(UUID.fromString(id)).thenApply { it }
        }
    }
}
