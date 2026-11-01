package org.misarch.shoppingcart.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.shoppingcart.event.model.OrderDTO
import org.misarch.shoppingcart.event.model.ProductVariantDTO
import org.misarch.shoppingcart.event.model.UserDTO
import org.misarch.shoppingcart.service.ProductVariantService
import org.misarch.shoppingcart.service.ShoppingCartService
import org.misarch.shoppingcart.service.UserService
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
 * @param shoppingCartService the shopping cart service
 */
@Controller
class EventController(
    private val userService: UserService,
    private val productVariantService: ProductVariantService,
    private val shoppingCartService: ShoppingCartService,
) {

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the created user
     */
    @Topic(name = ShoppingcartEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ShoppingcartEvents.USER_CREATED}")
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
    @Topic(name = ShoppingcartEvents.PRODUCT_VARIANT_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ShoppingcartEvents.PRODUCT_VARIANT_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantDTO>
    ) {
        productVariantService.registerProductVariant(cloudEvent.data)
    }

    /**
     * Handles an order created event: the ordered items leave the shopping cart
     *
     * @param cloudEvent the cloud event containing the created order
     */
    @Topic(name = ShoppingcartEvents.ORDER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${ShoppingcartEvents.ORDER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onOrderCreated(
        @RequestBody
        cloudEvent: CloudEvent<OrderDTO>
    ) {
        shoppingCartService.removeOrderedItems(cloudEvent.data)
    }

}
