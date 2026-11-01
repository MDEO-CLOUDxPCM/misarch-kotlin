package org.misarch.order.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.common.event.PUBSUB_NAME
import org.misarch.order.event.model.CouponDTO
import org.misarch.order.event.model.ProductVariantDTO
import org.misarch.order.event.model.ProductVariantVersionDTO
import org.misarch.order.event.model.ShipmentFailedDTO
import org.misarch.order.event.model.ShipmentMethodDTO
import org.misarch.order.event.model.TaxRateVersionDTO
import org.misarch.order.event.model.UserAddressDTO
import org.misarch.order.event.model.UserDTO
import org.misarch.order.service.CouponService
import org.misarch.order.service.OrderCompensationService
import org.misarch.order.service.ProductVariantService
import org.misarch.order.service.ShipmentMethodService
import org.misarch.order.service.TaxRateService
import org.misarch.order.service.UserService
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
    private val productVariantService: ProductVariantService,
    private val couponService: CouponService,
    private val taxRateService: TaxRateService,
    private val shipmentMethodService: ShipmentMethodService,
    private val userService: UserService,
    private val orderCompensationService: OrderCompensationService
) {

    /**
     * Handles a product variant updated event
     *
     * @param cloudEvent the cloud event containing the updated product variant
     */
    @Topic(name = OrderEvents.PRODUCT_VARIANT_UPDATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.PRODUCT_VARIANT_UPDATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantUpdated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantDTO>
    ) {
        productVariantService.updateProductVariant(cloudEvent.data)
    }

    /**
     * Handles a product variant version created event
     *
     * @param cloudEvent the cloud event containing the created product variant version
     */
    @Topic(name = OrderEvents.PRODUCT_VARIANT_VERSION_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.PRODUCT_VARIANT_VERSION_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantVersionCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantVersionDTO>
    ) {
        productVariantService.registerProductVariantVersion(cloudEvent.data)
    }

    /**
     * Handles a coupon created event
     *
     * @param cloudEvent the cloud event containing the created coupon
     */
    @Topic(name = OrderEvents.COUPON_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.COUPON_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onCouponCreated(
        @RequestBody
        cloudEvent: CloudEvent<CouponDTO>
    ) {
        couponService.registerCoupon(cloudEvent.data)
    }

    /**
     * Handles a tax rate version created event
     *
     * @param cloudEvent the cloud event containing the created tax rate version
     */
    @Topic(name = OrderEvents.TAX_RATE_VERSION_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.TAX_RATE_VERSION_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onTaxRateVersionCreated(
        @RequestBody
        cloudEvent: CloudEvent<TaxRateVersionDTO>
    ) {
        taxRateService.registerTaxRateVersion(cloudEvent.data)
    }

    /**
     * Handles a shipment method created event
     *
     * @param cloudEvent the cloud event containing the created shipment method
     */
    @Topic(name = OrderEvents.SHIPMENT_METHOD_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.SHIPMENT_METHOD_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentMethodCreated(
        @RequestBody
        cloudEvent: CloudEvent<ShipmentMethodDTO>
    ) {
        shipmentMethodService.registerShipmentMethod(cloudEvent.data)
    }

    /**
     * Handles a user created event
     *
     * @param cloudEvent the cloud event containing the created user
     */
    @Topic(name = OrderEvents.USER_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.USER_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserDTO>
    ) {
        userService.registerUser(cloudEvent.data)
    }

    /**
     * Handles a user address created event
     *
     * @param cloudEvent the cloud event containing the created user address
     */
    @Topic(name = OrderEvents.USER_ADDRESS_CREATED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.USER_ADDRESS_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserAddressCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserAddressDTO>
    ) {
        userService.registerUserAddress(cloudEvent.data)
    }

    /**
     * Handles a user address archived event
     *
     * @param cloudEvent the cloud event containing the archived user address
     */
    @Topic(name = OrderEvents.USER_ADDRESS_ARCHIVED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.USER_ADDRESS_ARCHIVED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserAddressArchived(
        @RequestBody
        cloudEvent: CloudEvent<UserAddressDTO>
    ) {
        userService.archiveUserAddress(cloudEvent.data)
    }

    /**
     * Handles a shipment creation failed event
     *
     * @param cloudEvent the cloud event containing the failed shipment
     */
    @Topic(name = OrderEvents.SHIPMENT_CREATION_FAILED, pubsubName = PUBSUB_NAME)
    @PostMapping("/subscription/${OrderEvents.SHIPMENT_CREATION_FAILED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onShipmentCreationFailed(
        @RequestBody
        cloudEvent: CloudEvent<ShipmentFailedDTO>
    ) {
        orderCompensationService.compensateOrderItems(cloudEvent.data)
    }

}
