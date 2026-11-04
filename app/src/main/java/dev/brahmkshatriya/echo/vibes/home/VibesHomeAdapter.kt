package dev.brahmkshatriya.echo.vibes.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.RecyclerView
import dev.brahmkshatriya.echo.R
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.databinding.ItemVibesHomeHeaderBinding
import dev.brahmkshatriya.echo.databinding.ItemVibesQuickTileBinding
import dev.brahmkshatriya.echo.history.HistoryRepository
import dev.brahmkshatriya.echo.playback.MediaItemUtils.trackRadioPlaceholder
import dev.brahmkshatriya.echo.ui.common.FragmentUtils.openFragment
import dev.brahmkshatriya.echo.ui.common.GridAdapter
import dev.brahmkshatriya.echo.ui.player.PlayerViewModel
import dev.brahmkshatriya.echo.ui.settings.SettingsLookFragment
import dev.brahmkshatriya.echo.utils.ContextUtils.observe
import dev.brahmkshatriya.echo.utils.image.ImageUtils.loadInto
import org.koin.android.ext.android.inject
import java.util.Calendar

/**
 * Spotify-style top of Home: time-of-day greeting, a theme shortcut, and a two-column grid of
 * "quick picks" made of the 8 most recently played tracks (tap = play it, with its radio after).
 */
class VibesHomeAdapter(private val fragment: Fragment) :
    RecyclerView.Adapter<VibesHomeAdapter.ViewHolder>(), GridAdapter {

    override val adapter = this
    override fun getSpanSize(position: Int, width: Int, count: Int) = count
    override fun getItemCount() = 1

    private data class Pick(val extensionId: String, val track: Track)

    private var picks: List<Pick> = emptyList()
    private var animated = false

    init {
        val repo by fragment.inject<HistoryRepository>()
        fragment.observe(repo.getHistory()) { list ->
            val new = list.mapNotNull { e -> e.track?.let { Pick(e.extensionId, it) } }
                .distinctBy { "${it.extensionId}:${it.track.id}" }.take(8)
            if (new.map { it.track.id } != picks.map { it.track.id }) {
                picks = new
                notifyItemChanged(0)
            }
        }
    }

    class ViewHolder(val b: ItemVibesHomeHeaderBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemVibesHomeHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val b = holder.b
        b.greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..12 -> "Buongiorno"
            in 13..17 -> "Buon pomeriggio"
            else -> "Buonasera"
        }
        b.themeButton.setOnClickListener { fragment.openFragment<SettingsLookFragment>() }

        b.grid.removeAllViews()
        b.grid.isVisible = picks.isNotEmpty()
        val ctx = b.root.context
        val inflater = LayoutInflater.from(ctx)
        picks.chunked(2).forEachIndexed { rowIndex, pair ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { if (rowIndex > 0) topMargin = (8 * ctx.resources.displayMetrics.density).toInt() }
            }
            pair.forEachIndexed { i, pick ->
                val tile = ItemVibesQuickTileBinding.inflate(inflater, row, false)
                tile.title.text = pick.track.title
                (pick.track.cover ?: pick.track.album?.cover).loadInto(tile.cover, R.drawable.art_music, R.drawable.art_music)
                tile.root.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i == 0) marginEnd = (4 * ctx.resources.displayMetrics.density).toInt()
                    else marginStart = (4 * ctx.resources.displayMetrics.density).toInt()
                }
                tile.root.setOnClickListener { play(pick) }
                row.addView(tile.root)
                if (!animated) {
                    tile.root.alpha = 0f
                    tile.root.translationY = 24 * ctx.resources.displayMetrics.density
                    tile.root.animate().alpha(1f).translationY(0f)
                        .setStartDelay(60L * (rowIndex * 2 + i)).setDuration(420).setInterpolator(EASE).start()
                }
            }
            if (pair.size == 1) row.addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            b.grid.addView(row)
        }
        if (picks.isNotEmpty()) animated = true
    }

    private fun play(pick: Pick) {
        val player by fragment.activityViewModels<PlayerViewModel>()
        player.setQueue(pick.extensionId, listOf(pick.track), 0, trackRadioPlaceholder(pick.track))
        player.setPlaying(true)
    }

    companion object {
        private val EASE = PathInterpolator(0.2f, 0f, 0f, 1f)
    }
}
