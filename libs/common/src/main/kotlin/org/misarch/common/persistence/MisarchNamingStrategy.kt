package org.misarch.common.persistence

import org.springframework.data.relational.core.mapping.NamingStrategy
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty

/**
 * Table name = simple class name, column name = property name, both unquoted (so Postgres folds them to lower
 * case), as in the Flyway schemas (`CREATE TABLE AddressEntity (postalCode ...)`). Spring Data's default would be
 * snake_case.
 */
object MisarchNamingStrategy : NamingStrategy {

    override fun getTableName(type: Class<*>): String = type.simpleName

    override fun getColumnName(property: RelationalPersistentProperty): String = property.name
}
