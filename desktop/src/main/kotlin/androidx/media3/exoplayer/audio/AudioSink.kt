package androidx.media3.exoplayer.audio

import android.content.Context
import androidx.media3.common.audio.AudioProcessor

interface AudioSink

class DefaultAudioSink private constructor(val processors: List<AudioProcessor>) : AudioSink {
    class Builder(context: Context? = null) {
        private var p: List<AudioProcessor> = emptyList()
        fun setAudioProcessors(arr: Array<AudioProcessor>) = apply { p = arr.toList() }
        fun setAudioProcessorChain(c: Any?) = this
        fun setEnableFloatOutput(b: Boolean) = this
        fun setEnableAudioTrackPlaybackParams(b: Boolean) = this
        fun build() = DefaultAudioSink(p)
    }
}
