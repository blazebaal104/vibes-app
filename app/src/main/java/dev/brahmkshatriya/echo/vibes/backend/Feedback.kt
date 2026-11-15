package dev.brahmkshatriya.echo.vibes.backend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/** One report sent from "Segnala un problema" (bug, idea or other), with the admin's answer. */
@Serializable
data class FeedbackItem(
    val id: String,
    val user_id: String,
    val kind: String = "bug",
    val message: String,
    val image_url: String? = null,
    val app_version: String? = null,
    val platform: String? = null,
    val device: String? = null,
    val screen: String? = null,
    val status: String = "nuovo",
    val reply: String? = null,
    val created_at: String = "",
)

/**
 * Users write problems and ideas from Settings; admins (vibes_admins) read them all, mark them
 * seen / fixed and answer. The server only lets each user read their own (RLS), and only admins
 * change status and answers.
 */
class FeedbackApi(private val supa: Supa) {
    private val json = Supa.json

    suspend fun send(kind: String, message: String, screenshot: ByteArray?, appVersion: String, platform: String, device: String, screen: String?) {
        val me = supa.userId ?: error("Accedi per inviare")
        val image = screenshot?.let {
            val path = "$me/feedback_${System.currentTimeMillis()}.jpg"
            supa.upload("vibes-avatars", path, it, "image/jpeg")
            "${Supa.URL}/storage/v1/object/public/vibes-avatars/$path"
        }
        supa.insert("vibes_feedback", buildJsonObject {
            put("user_id", me); put("kind", kind); put("message", message.trim())
            image?.let { put("image_url", it) }
            put("app_version", appVersion); put("platform", platform); put("device", device)
            screen?.let { put("screen", it) }
        }, returning = false)
    }

    suspend fun mine(): List<FeedbackItem> {
        val me = supa.userId ?: return emptyList()
        return supa.select("vibes_feedback", "user_id=eq.$me&order=created_at.desc&limit=50").map { json.decodeFromJsonElement(it) }
    }

    /** Admins: everything (RLS returns only your own to everyone else). */
    suspend fun all(): List<FeedbackItem> =
        supa.select("vibes_feedback", "order=created_at.desc&limit=300").map { json.decodeFromJsonElement(it) }

    suspend fun update(id: String, status: String, reply: String?) {
        supa.update("vibes_feedback", "id=eq.$id", buildJsonObject {
            put("status", status)
            if (reply.isNullOrBlank()) put("reply", JsonNull) else put("reply", reply.trim())
            put("updated_at", java.time.Instant.now().toString())
        })
    }

    suspend fun delete(id: String) = supa.delete("vibes_feedback", "id=eq.$id")
}
