package org.misarch.common.testing

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import org.springframework.beans.factory.config.BeanPostProcessor
import reactor.core.publisher.Mono
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records the SQL statements the application sends over R2DBC, so that a test can assert which tables a GraphQL
 * operation reads (e.g. that a query not selecting a child connection does not read the child table):
 * ```
 * @Import(SqlStatementLog::class)
 * class FooIntegrationTest : MisarchIntegrationTest() {
 *     @Test fun `does not load items`() {
 *         val statements = SqlStatementLog.record { graphql("{ order(id: ...) { id } }") }
 *         assertThat(statements).noneMatch { "order_items" in it }
 *     }
 * }
 * ```
 * Importing it registers a [BeanPostProcessor] that wraps the application's [ConnectionFactory] (a dynamic proxy with
 * all interfaces of the wrapped factory, e.g. the pool's `Disposable`/`Closeable`); every `Connection.createStatement(sql)`
 * is recorded, transaction boundaries as `BEGIN` / `COMMIT` / `ROLLBACK`. Flyway (JDBC) is not recorded. The log is
 * global (one per test JVM; tests run sequentially).
 */
class SqlStatementLog : BeanPostProcessor {

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
        if (bean is ConnectionFactory && !Proxy.isProxyClass(bean.javaClass)) proxy(bean, ConnectionFactoryHandler(bean)) else bean

    companion object {
        private val statements = CopyOnWriteArrayList<String>()

        /** Whitespace of multi-line queries collapsed to single spaces. */
        private fun oneLine(sql: String): String = sql.trim().replace(WHITESPACE, " ")

        private val WHITESPACE = Regex("\\s+")

        /** Runs [block] and returns the statements recorded meanwhile (in execution order; whitespace collapsed). */
        fun record(block: () -> Unit): List<String> {
            statements.clear()
            block()
            return statements.toList()
        }

        private fun proxy(target: Any, handler: InvocationHandler): Any =
            Proxy.newProxyInstance(target.javaClass.classLoader, interfaces(target.javaClass), handler)

        private fun interfaces(type: Class<*>): Array<Class<*>> {
            val result = LinkedHashSet<Class<*>>()
            var current: Class<*>? = type
            while (current != null) {
                current.interfaces.forEach { collect(it, result) }
                current = current.superclass
            }
            return result.filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }.toTypedArray()
        }

        private fun collect(type: Class<*>, into: MutableSet<Class<*>>) {
            if (into.add(type)) type.interfaces.forEach { collect(it, into) }
        }

        private fun forward(target: Any, method: Method, args: Array<out Any?>?): Any? =
            try {
                method.invoke(target, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
    }

    private class ConnectionFactoryHandler(private val target: ConnectionFactory) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "create" && method.parameterCount == 0) {
                return Mono.from(target.create()).map { connection -> proxy(connection, ConnectionHandler(connection)) as Connection }
            }
            return forward(target, method, args)
        }
    }

    private class ConnectionHandler(private val target: Connection) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "createStatement" -> statements.add(oneLine(args!![0] as String))
                "beginTransaction" -> statements.add("BEGIN")
                "commitTransaction" -> statements.add("COMMIT")
                "rollbackTransaction" -> statements.add("ROLLBACK")
            }
            return forward(target, method, args)
        }
    }
}
