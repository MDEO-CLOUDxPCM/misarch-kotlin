package org.misarch.review.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.review.event.model.ProductDTO
import org.misarch.review.event.model.ProductVariantDTO
import org.misarch.review.event.model.UserDTO
import org.misarch.review.service.ProductService
import org.misarch.review.service.ProductVariantService
import org.misarch.review.service.UserService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 */
@Controller
class EventController(
    private val userService: UserService,
    private val productService: ProductService,
    private val productVariantService: ProductVariantService,
) {

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the created user
     */
    @Topic(name = ReviewEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReviewEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserDTO>
    ) {
        userService.registerUser(cloudEvent.data)
    }

    /**
     * Handles a product created event
     *
     * @param cloudEvent the cloud event containing the created product
     */
    @Topic(name = ReviewEvents.PRODUCT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReviewEvents.PRODUCT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductDTO>
    ) {
        productService.registerProduct(cloudEvent.data)
    }

    /**
     * Handles a product variant created event
     *
     * @param cloudEvent the cloud event containing the created product variant
     */
    @Topic(name = ReviewEvents.PRODUCT_VARIANT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ReviewEvents.PRODUCT_VARIANT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantDTO>
    ) {
        productVariantService.registerProductVariant(cloudEvent.data)
    }

}
