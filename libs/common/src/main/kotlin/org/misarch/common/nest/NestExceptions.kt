package org.misarch.common.nest

/**
 * NestJS `HttpException`s as thrown by the original code. In GraphQL they are formatted like `@nestjs/apollo` 12
 * does ([NestGraphQLErrors]); in REST handlers they become the Nest JSON error body ([NestRest]).
 *
 * @property status HTTP status
 * @property response the Nest response object (`{message, error, statusCode}`), in this key order
 */
open class HttpException(
    val status: Int,
    message: String,
    val response: Map<String, Any?>,
) : RuntimeException(message)

class NotFoundException(message: String) :
    HttpException(404, message, linkedMapOf("message" to message, "error" to "Not Found", "statusCode" to 404))

class UnauthorizedException(message: String) :
    HttpException(401, message, linkedMapOf("message" to message, "error" to "Unauthorized", "statusCode" to 401))

class ForbiddenException(message: String = "Forbidden resource") :
    HttpException(403, message, linkedMapOf("message" to message, "error" to "Forbidden", "statusCode" to 403))

/** `ValidationPipe` failure: message `Bad Request Exception`, response `{message: [...], error, statusCode}`. */
class BadRequestException(val messages: List<String>) :
    HttpException(400, "Bad Request Exception", linkedMapOf("message" to messages, "error" to "Bad Request", "statusCode" to 400))

/**
 * A JavaScript runtime error of the original (`TypeError`, `SyntaxError`, Mongoose errors, non-null violations) whose
 * message is reproduced verbatim; formatted as `INTERNAL_SERVER_ERROR` without `originalError`.
 */
class JsError(message: String) : RuntimeException(message)
