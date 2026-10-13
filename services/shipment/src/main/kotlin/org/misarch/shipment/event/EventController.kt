package org.misarch.shipment.event

import io.dapr.Topic
import org.misarch.common.event.CloudEvent
import org.misarch.shipment.event.model.PaymentEnabledDTO
import org.misarch.shipment.event.model.ProductVariantVersionDTO
import org.misarch.shipment.event.model.ReturnDTO
import org.misarch.shipment.event.model.UserAddressDTO
import org.misarch.shipment.event.model.VendorAddressDTO
import org.misarch.shipment.service.AddressService
import org.misarch.shipment.service.ProductVariantVersionService
import org.misarch.shipment.service.ShipmentService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Controller for dapr events
 *
 * @param addressService the user service
 * @param productVariantVersionService the product variant version service
 * @param shipmentService the shipment service
 */
@Controller
class EventController(
    private val addressService: AddressService,
    private val productVariantVersionService: ProductVariantVersionService,
    private val shipmentService: ShipmentService
) {

    /**
     * Handles a user address created event
     *
     * @param cloudEvent the cloud event containing the user address created
     */
    @Topic(name = ShipmentEvents.USER_ADDRESS_CREATED, pubsubName = ShipmentEvents.PUBSUB_NAME)
    @PostMapping("/subscription/${ShipmentEvents.USER_ADDRESS_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onUserCreated(
        @RequestBody
        cloudEvent: CloudEvent<UserAddressDTO>
    ) {
        addressService.registerUserAddress(cloudEvent.data)
    }

    /**
     * Handles a vendor address created event
     *
     * @param cloudEvent the cloud event containing the vendor address created
     */
    @Topic(name = ShipmentEvents.VENDOR_ADDRESS_CREATED, pubsubName = ShipmentEvents.PUBSUB_NAME)
    @PostMapping("/subscription/${ShipmentEvents.VENDOR_ADDRESS_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onVendorCreated(
        @RequestBody
        cloudEvent: CloudEvent<VendorAddressDTO>
    ) {
        addressService.registerVendorAddress(cloudEvent.data)
    }

    /**
     * Handles a product variant version created event
     *
     * @param cloudEvent the cloud event containing the product variant version created
     */
    @Topic(name = ShipmentEvents.PRODUCT_VARIANT_VERSION_CREATED, pubsubName = ShipmentEvents.PUBSUB_NAME)
    @PostMapping("/subscription/${ShipmentEvents.PRODUCT_VARIANT_VERSION_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    suspend fun onProductVariantVersionCreated(
        @RequestBody
        cloudEvent: CloudEvent<ProductVariantVersionDTO>
    ) {
        productVariantVersionService.registerProductVariantVersion(cloudEvent.data)
    }

    /**
     * Handles a payment enabled event (one transaction for all shipments of the order, like the original)
     *
     * @param cloudEvent the cloud event containing the order for which payment has been enabled
     */
    @Topic(name = ShipmentEvents.PAYMENT_ENABLED, pubsubName = ShipmentEvents.PUBSUB_NAME)
    @PostMapping("/subscription/${ShipmentEvents.PAYMENT_ENABLED}")
    @ResponseStatus(code = HttpStatus.OK)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    suspend fun onPaymentEnabled(
        @RequestBody
        cloudEvent: CloudEvent<PaymentEnabledDTO>
    ) {
        shipmentService.createShipmentForOrder(cloudEvent.data)
    }

    /**
     * Handles a return created event
     *
     * @param cloudEvent the cloud event containing the return for which a shipment has been created
     */
    @Topic(name = ShipmentEvents.RETURN_CREATED, pubsubName = ShipmentEvents.PUBSUB_NAME)
    @PostMapping("/subscription/${ShipmentEvents.RETURN_CREATED}")
    @ResponseStatus(code = HttpStatus.OK)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    suspend fun onReturnCreated(
        @RequestBody
        cloudEvent: CloudEvent<ReturnDTO>
    ) {
        shipmentService.createShipmentForReturn(cloudEvent.data)
    }

}
