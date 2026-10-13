package org.misarch.invoice.graphql.model

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import com.expediagroup.graphql.generator.federation.directives.FieldSet
import com.expediagroup.graphql.generator.federation.directives.KeyDirective
import java.time.Instant
import java.util.UUID

/** Invoice of an order (`graphql/model/invoice.rs`; the fields have no descriptions in the original). */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("Invoice of an order.")
class Invoice(
    val id: UUID,
    val orderId: UUID,
    val issuedAt: Instant,
    val content: String,
    val userAddress: UserAddress,
    val vendorAddress: VendorAddress,
    val vatNumber: String?,
)

/** Foreign type of an order. */
@KeyDirective(fields = FieldSet("id"))
@GraphQLDescription("Foreign type of an order.")
class Order(
    @property:GraphQLDescription("UUID of the order.")
    val id: UUID,
    @property:GraphQLDescription("Invoice of the order.")
    val invoice: Invoice,
)

/** Foreign type of a user address. */
@KeyDirective(fields = FieldSet("id"), resolvable = false)
@GraphQLDescription("Foreign type of a user address.")
class UserAddress(val id: UUID)

/** Foreign type of a vendor address. */
@KeyDirective(fields = FieldSet("id"), resolvable = false)
@GraphQLDescription("Foreign type of a vendor address.")
class VendorAddress(val id: UUID)
