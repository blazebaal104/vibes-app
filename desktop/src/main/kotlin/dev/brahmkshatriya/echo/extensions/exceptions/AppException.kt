package dev.brahmkshatriya.echo.extensions.exceptions

import dev.brahmkshatriya.echo.common.models.Metadata

sealed class AppException : Exception() {
    abstract val extension: Metadata
    open class LoginRequired(override val extension: Metadata) : AppException()
    class Unauthorized(override val extension: Metadata, val userId: String) : LoginRequired(extension)
    class NotSupported(override val cause: Throwable, override val extension: Metadata) : AppException()
    class Other(override val cause: Throwable, override val extension: Metadata) : AppException()
}
