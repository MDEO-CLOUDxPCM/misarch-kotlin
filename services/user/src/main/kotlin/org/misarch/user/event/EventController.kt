package org.misarch.user.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.user.event.model.CreateUserDTO
import org.misarch.user.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for Dapr events
 *
 * @param userService the service for users
 */
@Controller
class EventController(
    private val userService: UserService
) {

    /**
     * Handles user creation events (published by Keycloak)
     *
     * @param cloudEvent the event to handle
     */
    @Topic(name = UserEvents.USER_CREATE, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${UserEvents.USER_CREATE}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreate(
        @RequestBody
        cloudEvent: CloudEvent<CreateUserDTO>
    ) {
        userService.createUser(cloudEvent.data)
    }

}
