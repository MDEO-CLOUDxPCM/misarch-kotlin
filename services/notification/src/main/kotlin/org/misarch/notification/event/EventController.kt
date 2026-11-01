package org.misarch.notification.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.notification.event.model.NotificationDTO
import org.misarch.notification.event.model.UserDTO
import org.misarch.notification.service.NotificationService
import org.misarch.notification.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @param notificationService the notification service
 * @param userService the user service
 */
@Controller
class EventController(
    private val notificationService: NotificationService,
    private val userService: UserService
) {

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the user created
     */
    @Topic(name = NotificationEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${NotificationEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserDTO>
    ) {
        userService.registerUser(cloudEvent.data)
    }

    /**
     * Handles a notification creation event (no service publishes this topic in MiSArch)
     *
     * @param cloudEvent the cloud event containing the notification to create
     */
    @Topic(name = NotificationEvents.NOTIFICATION_CREATE, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${NotificationEvents.NOTIFICATION_CREATE}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onNotificationCreate(
        @RequestBody
        cloudEvent: CloudEvent<NotificationDTO>
    ) {
        notificationService.createNotification(cloudEvent.data)
    }

}
