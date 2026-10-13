package org.misarch.catalog.persistence.model

import org.misarch.catalog.graphql.model.CategoricalCategoryCharacteristic
import org.misarch.catalog.graphql.model.CategoryCharacteristic
import org.misarch.catalog.graphql.model.NumericalCategoryCharacteristic
import org.misarch.common.persistence.BaseEntity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.*

/**
 * Entity for category characteristics
 * Two types of characteristics are supported:
 * 1. Categorical - characteristic with a set of possible values
 * 2. Numerical - characteristic with a numerical value and a unit
 *
 * @property discriminator discriminator to distinguish between categorical and numerical characteristics
 * @property name name of the characteristic
 * @property description description of the characteristic
 * @property unit unit of the numerical characteristic, null for categorical characteristics
 * @property categoryId id of the category this characteristic belongs to
 * @property id unique identifier of the characteristic
 */
@Table
class CategoryCharacteristicEntity(
    val discriminator: CategoryCharacteristicDiscriminator,
    val name: String,
    val description: String,
    val unit: String?,
    val categoryId: UUID,
    @Id
    override val id: UUID?
) : BaseEntity<CategoryCharacteristic> {

    override fun toDTO(): CategoryCharacteristic {
        return when (discriminator) {
            CategoryCharacteristicDiscriminator.CATEGORICAL -> CategoricalCategoryCharacteristic(
                id = id!!,
                name = name,
                description = description,
                categoryId = categoryId
            )

            CategoryCharacteristicDiscriminator.NUMERICAL -> NumericalCategoryCharacteristic(
                id = id!!,
                name = name,
                description = description,
                categoryId = categoryId,
                unit = unit!!
            )
        }
    }

}