package dev.brahmkshatriya.echo.vibes.listen

/**
 * Transport commands from OUTSIDE the app (notification, lock screen, headphones, watch) while in a
 * listening lobby. The session callback hands them here first: when [handler] returns true the
 * lobby took the command (as the king's action, for the whole group) and the player must not run it.
 */
object LobbyGate {
    @Volatile var handler: ((Int) -> Boolean)? = null
}
