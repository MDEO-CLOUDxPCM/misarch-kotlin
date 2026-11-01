package org.misarch.notification.graphql

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.server.operations.Mutation
import graphql.schema.DataFetchingEnvironment
import org.misarch.common.graphql.authorizedUser
import org.misarch.notification.graphql.input.CreateNotificationInput
import org.misarch.notification.graphql.input.UpdateNotificationInput
import org.misarch.notification.graphql.model.Notification
import org.misarch.notification.service.NotificationService
import org.springframework.stereotype.Component

/**
 * Defines GraphQL mutations
 *
 * @property notificationService service used to create and update notifications
 */
@Component
class Mutation(
    private val notificationService: NotificationService
) : Mutation {

    @GraphQLDescription("Create a new notification")
    suspend fun createNotification(
        @GraphQLDescription("Input for the createNotification mutation")
        input: CreateNotificationInput,
        dfe: DataFetchingEnvironment
    ): Notification {
        dfe.authorizedUser.checkIsEmployee()
        val notification = notificationService.createNotification(input)
        return notification.toDTO()
    }

    @GraphQLDescription("Update a notification")
    suspend fun updateNotification(
        @GraphQLDescription("Input for the updateNotification mutation")
        input: UpdateNotificationInput,
        dfe: DataFetchingEnvironment
    ): Notification {
        val notification = notificationService.updateNotification(input, dfe.authorizedUser)
        return notification.toDTO()
    }
}
