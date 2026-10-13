package org.misarch.order.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import org.misarch.order.graphql.model.CommonOrderField
import org.misarch.order.graphql.model.OrderDirection
import org.misarch.order.graphql.model.OrderOrderField
import java.util.UUID

class CreateOrderInput(
    @property:GraphQLDescription("UUID of user owning the order.")
    val userId: UUID,
    @property:GraphQLDescription("OrderItems of order.")
    val orderItemInputs: List<OrderItemInput>,
    @property:GraphQLDescription("UUID of address to where the order should be shipped to.")
    val shipmentAddressId: UUID,
    @property:GraphQLDescription("UUID of address of invoice.")
    val invoiceAddressId: UUID,
    @property:GraphQLDescription("UUID of payment information that the order should be processed with.")
    val paymentInformationId: UUID,
    @property:GraphQLDescription("Optional VAT number.")
    val vatNumber: String? = null,
)

class OrderItemInput(
    @property:GraphQLDescription("UUID of shopping cart item associated with order item.")
    val shoppingCartItemId: UUID,
    @property:GraphQLDescription("UUID of shipment method to use with order item.")
    val shipmentMethodId: UUID,
    @property:GraphQLDescription("UUIDs of coupons to use with order item.")
    val couponIds: List<UUID>,
)

class PlaceOrderInput(
    @property:GraphQLDescription("UUID of order to place.")
    val id: UUID,
    @property:GraphQLDescription("Optional payment authorization data.")
    val paymentAuthorization: PaymentAuthorizationInput? = null,
)

class PaymentAuthorizationInput(
    @property:GraphQLDescription("CVC/CVV number of 3-4 digits.")
    val cvc: Int? = null,
)

@GraphQLDescription("Specifies the order of orders.")
class OrderOrderInput(
    @property:GraphQLDescription("Order direction of orders.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that orders should be ordered by.")
    val field: OrderOrderField? = null,
)

@GraphQLDescription("Specifies the order of foreign types.")
class CommonOrderInput(
    @property:GraphQLDescription("Order direction of orders.")
    val direction: OrderDirection? = null,
    @property:GraphQLDescription("Field that orders should be ordered by.")
    val field: CommonOrderField? = null,
)
