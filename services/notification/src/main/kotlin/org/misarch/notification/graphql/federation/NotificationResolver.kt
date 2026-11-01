package org.misarch.notification.graphql.federation

import org.misarch.common.graphql.DataLoaderEntityResolver
import org.misarch.notification.graphql.dataloader.NotificationDataLoader
import org.misarch.notification.graphql.model.Notification
import org.springframework.stereotype.Component

/**
 * Federated resolver for [Notification]s (no authorization check, like in the original).
 */
@Component
class NotificationResolver : DataLoaderEntityResolver<Notification>(Notification::class, NotificationDataLoader::class)
