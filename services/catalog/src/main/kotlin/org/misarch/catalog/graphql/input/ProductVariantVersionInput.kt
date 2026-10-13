package org.misarch.catalog.graphql.input

import com.expediagroup.graphql.generator.annotations.GraphQLDescription
import java.util.UUID

@GraphQLDescription("Input for creating a ProductVariantVersion.")
open class ProductVariantVersionInput(
    @property:GraphQLDescription("The name of the ProductVariant.")
    val name: String,
    @property:GraphQLDescription("The description of the ProductVariant.")
    val description: String,
    @property:GraphQLDescription("The retail price of the ProductVariant.")
    val retailPrice: Int,
    @property:GraphQLDescription("The amount of days for which an instance of the ProductVariant can be returned after purchase, if null can be returned indefinitely.")
    val canBeReturnedForDays: Int?,
    @property:GraphQLDescription("The CategoricalCategoryCharacteristicValues of the ProductVariant, must be compatible with the Categories of the associated Product.")
    val categoricalCharacteristicValues: List<CategoricalCategoryCharacteristicValueInput>,
    @property:GraphQLDescription("The NumericalCategoryCharacteristicValues of the ProductVariant, must be compatible with the Categories of the associated Product.")
    val numericalCharacteristicValues: List<NumericalCategoryCharacteristicValueInput>,
    @property:GraphQLDescription("The associated TaxRate")
    val taxRateId: UUID,
    @property:GraphQLDescription("The weight of a single instance of the ProductVariant.")
    val weight: Double,
    @property:GraphQLDescription("The associated Media files.")
    val mediaIds: List<UUID>
)