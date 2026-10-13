package org.misarch.catalog.persistence.model

import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristicValue
import org.misarch.catalog.graphql.model.CategoryCharacteristicValue
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristicValue
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for category characteristic values
 * Two types of characteristic values are supported:
 * Categorical characteristic values have [stringValue] set and [doubleValue] null
 * Numerical characteristic values have [doubleValue] set and [stringValue] null
 *
 * @property discriminator discriminator to distinguish between categorical and numerical characteristics values
 * @property stringValue string value of the characteristic value, null for numerical characteristic values
 * @property doubleValue double value of the characteristic value, null for categorical characteristic values
 * @property categoryCharacteristicId id of the referenced characteristic
 * @property productVariantVersionId id of the referenced product variant version owning the value
 * @property id unique identifier of the characteristic value
 */
@Table
class CategoryCharacteristicValueEntity(
    val discriminator: CategoryCharacteristicDiscriminator,
    val stringValue: String?,
    val doubleValue: Double?,
    val categoryCharacteristicId: UUID,
    val productVariantVersionId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<CategoryCharacteristicValue> {

    override fun toDTO(): CategoryCharacteristicValue {
        return when (discriminator) {
            CategoryCharacteristicDiscriminator.CATEGORICAL -> CategoricalCategoryCharacteristicValue(
                categoryCharacteristicId,
                stringValue!!
            )

            CategoryCharacteristicDiscriminator.NUMERICAL -> NumericalCategoryCharacteristicValue(
                categoryCharacteristicId,
                doubleValue!!
            )
        }
    }

}