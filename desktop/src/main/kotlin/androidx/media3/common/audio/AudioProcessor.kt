package androidx.media3.common.audio

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface AudioProcessor {
    data class AudioFormat(val sampleRate: Int, val channelCount: Int, val encoding: Int) {
        val bytesPerFrame get() = channelCount * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        companion object { val NOT_SET = AudioFormat(-1, -1, C.ENCODING_INVALID) }
    }
    class UnhandledAudioFormatException(f: AudioFormat) : Exception("Unhandled format: $f")

    fun configure(inputAudioFormat: AudioFormat): AudioFormat
    fun isActive(): Boolean
    fun queueInput(inputBuffer: ByteBuffer)
    fun queueEndOfStream()
    fun getOutput(): ByteBuffer
    fun isEnded(): Boolean
    fun flush()
    fun reset()

    companion object { val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()) }
}

/** Same contract as Media3's BaseAudioProcessor, so the phone's audio effects run unchanged on PC. */
abstract class BaseAudioProcessor : AudioProcessor {
    protected var inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    protected var outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var pendingInput = AudioProcessor.AudioFormat.NOT_SET
    private var pendingOutput = AudioProcessor.AudioFormat.NOT_SET
    private var buffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    final override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        pendingInput = inputAudioFormat
        pendingOutput = onConfigure(inputAudioFormat)
        return if (isActive()) pendingOutput else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun isActive() = pendingOutput != AudioProcessor.AudioFormat.NOT_SET

    override fun getOutput(): ByteBuffer { val o = outputBuffer; outputBuffer = AudioProcessor.EMPTY_BUFFER; return o }

    override fun queueEndOfStream() { inputEnded = true; onQueueEndOfStream() }

    override fun isEnded() = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    final override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        inputAudioFormat = pendingInput
        outputAudioFormat = pendingOutput
        onFlush()
    }

    final override fun reset() {
        flush()
        buffer = AudioProcessor.EMPTY_BUFFER
        pendingInput = AudioProcessor.AudioFormat.NOT_SET; pendingOutput = AudioProcessor.AudioFormat.NOT_SET
        inputAudioFormat = AudioProcessor.AudioFormat.NOT_SET; outputAudioFormat = AudioProcessor.AudioFormat.NOT_SET
        onReset()
    }

    protected fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (buffer.capacity() < size) buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        else buffer.clear()
        outputBuffer = buffer
        return buffer
    }

    protected fun hasPendingOutput() = outputBuffer.hasRemaining()

    protected open fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    protected open fun onQueueEndOfStream() {}
    protected open fun onFlush() {}
    protected open fun onReset() {}
}
