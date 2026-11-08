package dev.brahmkshatriya.echo.extensions.db.models

import dev.brahmkshatriya.echo.common.models.ExtensionType
import dev.brahmkshatriya.echo.common.models.User
import kotlinx.serialization.Serializable

@Serializable
data class CurrentUser(val type: ExtensionType, val extId: String, val userId: String?)

@Serializable
data class UserEntity(val type: ExtensionType, val extId: String, val id: String, val data: String) {
    val user: User get() = dev.brahmkshatriya.echo.extensions.ExtensionStore.json.decodeFromString(User.serializer(), data)
    companion object {
        fun User.toEntity(type: ExtensionType, extId: String) =
            UserEntity(type, extId, id, dev.brahmkshatriya.echo.extensions.ExtensionStore.json.encodeToString(User.serializer(), this))
        fun UserEntity.toCurrentUser() = CurrentUser(type, extId, id)
    }
}
