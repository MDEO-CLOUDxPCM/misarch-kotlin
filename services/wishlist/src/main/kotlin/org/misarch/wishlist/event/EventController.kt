package org.misarch.wishlist.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.wishlist.event.model.ProductVariantDTO
import org.misarch.wishlist.event.model.UserDTO
import org.misarch.wishlist.service.ProductVariantService
import org.misarch.wishlist.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @param userService the user service
 * @param productVariantService the product variant service
 */
@Controller
class EventController(
    private val userService: UserService,
    private val productVariantService: ProductVariantService,
) {

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the created user
     */
    @Topic(name = WishlistEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${WishlistEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserDTO>
    ) {
        userService.registerUser(cloudEvent.data)
    }

    /**
     * Handles a product variant created event
     *
     * @param cloudEvent the cloud event containing the created product variant
     */
    @Topic(name = WishlistEvents.PRODUCT_VARIANT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${WishlistEvents.PRODUCT_VARIANT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantDTO>
    ) {
        productVariantService.registerProductVariant(cloudEvent.data)
    }

}
