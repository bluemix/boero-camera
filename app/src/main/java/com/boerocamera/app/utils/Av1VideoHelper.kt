package com.boerocamera.app.utils

import android.content.ContentValues
import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * AV1+Opus → WebM via MediaCodec + MediaMuxer.
 *
 * MediaMuxer rules:
 *   - addTrack() for ALL tracks must complete before muxer.start()
 *   - addTrack() / start() / writeSampleData() / stop() are NOT thread-safe
 *   - INFO_OUTPUT_FORMAT_CHANGED fires exactly once per codec, before any data
 *
 * Design: encode threads never touch the muxer directly.
 * They post MuxerCommand objects to a single-consumer BlockingQueue.
 * One dedicated muxer thread dequeues commands serially, so all muxer calls
 * happen on one thread with no locking needed.
 *
 * Startup sequence:
 *   1. Video encode thread posts FORMAT_VIDEO when it sees INFO_OUTPUT_FORMAT_CHANGED
 *   2. Audio encode thread posts FORMAT_AUDIO
 *   3. Muxer thread receives both FORMAT commands, calls addTrack() for each,
 *      then calls muxer.start(), then starts accepting SAMPLE commands.
 *   4. Encode threads post SAMPLE commands which the muxer thread writes.
 *   5. On stop, a STOP command drains the queue and shuts down.
 */
object Av1VideoHelper {

    private const val TAG = "Av1VideoHelper"
    private const val MIME_VIDEO = MediaFormat.MIMETYPE_VIDEO_AV1
    private const val MIME_OPUS  = "audio/opus"
    private const val MIME_AAC   = MediaFormat.MIMETYPE_AUDIO_AAC

    // ─── Muxer commands ───────────────────────────────────────────────────────

    private sealed class MuxerCmd {
        data class FormatVideo(val format: MediaFormat) : MuxerCmd()
        data class FormatAudio(val format: MediaFormat) : MuxerCmd()
        data class Sample(val trackId: Int, val data: ByteArray, val pts: Long, val flags: Int) : MuxerCmd()
        object Stop : MuxerCmd()
    }

    // ─── Encoder probe ────────────────────────────────────────────────────────

    data class EncoderInfo(
        val available: Boolean, val codecName: String?,
        val isHardware: Boolean, val maxWidth: Int, val maxHeight: Int, val maxBitrate: Int
    )

    fun probeAv1Encoder(): EncoderInfo {
        for (info in MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME_VIDEO, ignoreCase = true) }) continue
            val isHw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) !info.isSoftwareOnly
                       else !info.name.startsWith("OMX.google") && !info.name.startsWith("c2.android")
            if (!isHw) continue
            return try {
                val vc = info.getCapabilitiesForType(MIME_VIDEO).videoCapabilities
                EncoderInfo(true, info.name, true,
                    vc.supportedWidths.upper, vc.supportedHeights.upper, vc.bitrateRange.upper)
            } catch (e: Exception) { EncoderInfo(true, info.name, true, 1920, 1080, 20_000_000) }
        }
        return EncoderInfo(false, null, false, 0, 0, 0)
    }

    private fun isEncoderAvailable(mime: String) =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

    // ─── Recording session ────────────────────────────────────────────────────

    class RecordingSession(
        val inputSurface: Surface,
        private val videoCodec: MediaCodec,
        private val audioCodec: MediaCodec?,
        private val audioRecord: AudioRecord?,
        private val muxer: MediaMuxer,
        private val pfd: ParcelFileDescriptor,
        private val uri: Uri,
        private val hasAudio: Boolean
    ) {
        val stopped = AtomicBoolean(false)
        private val queue = LinkedBlockingQueue<MuxerCmd>(512)

        // Track indices assigned by muxer thread after addTrack()
        private val videoTrack = AtomicInteger(-1)
        private val audioTrack = AtomicInteger(-1)

        // Called by video drain thread — just post the format, never touch muxer
        fun postVideoFormat(format: MediaFormat) = queue.put(MuxerCmd.FormatVideo(format))
        fun postAudioFormat(format: MediaFormat) = queue.put(MuxerCmd.FormatAudio(format))

        fun postVideoSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (info.size <= 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            val bytes = ByteArray(info.size)
            buf.position(info.offset); buf.get(bytes)
            queue.offer(MuxerCmd.Sample(videoTrack.get(), bytes, info.presentationTimeUs, info.flags))
        }

        fun postAudioSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
            if (info.size <= 0) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            val bytes = ByteArray(info.size)
            buf.position(info.offset); buf.get(bytes)
            queue.offer(MuxerCmd.Sample(audioTrack.get(), bytes, info.presentationTimeUs, info.flags))
        }

        // Muxer thread: dequeue and execute all commands serially
        fun runMuxerLoop() {
            var muxerStarted = false
            var videoFmtReceived = false
            var audioFmtReceived = false
            val pendingSamples = mutableListOf<MuxerCmd.Sample>()

            fun writeSample(cmd: MuxerCmd.Sample) {
                if (cmd.trackId < 0) return
                val wrap = java.nio.ByteBuffer.wrap(cmd.data)
                val info = MediaCodec.BufferInfo().also {
                    it.offset = 0; it.size = cmd.data.size
                    it.presentationTimeUs = cmd.pts; it.flags = cmd.flags
                }
                muxer.writeSampleData(cmd.trackId, wrap, info)
            }

            fun tryStartMuxer() {
                if (videoFmtReceived && (audioFmtReceived || !hasAudio) && !muxerStarted) {
                    muxer.start()
                    muxerStarted = true
                    Log.i(TAG, "Muxer started (video=${videoTrack.get()} audio=${audioTrack.get()})")
                    for (s in pendingSamples) writeSample(s)
                    pendingSamples.clear()
                }
            }

            // MediaMuxer on some devices rejects addTrack for AV1 if audio was
            // added first. Always add video track before audio track.
            var pendingAudioFormat: MediaFormat? = null

            while (true) {
                val cmd = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                when (cmd) {
                    is MuxerCmd.FormatVideo -> {
                        videoTrack.set(muxer.addTrack(cmd.format))
                        Log.i(TAG, "Video track added: ${videoTrack.get()}")
                        videoFmtReceived = true
                        // Now safe to add the audio track if it arrived earlier
                        pendingAudioFormat?.let { fmt ->
                            audioTrack.set(muxer.addTrack(fmt))
                            Log.i(TAG, "Audio track added (deferred): ${audioTrack.get()}")
                            audioFmtReceived = true
                            pendingAudioFormat = null
                        }
                        tryStartMuxer()
                    }
                    is MuxerCmd.FormatAudio -> {
                        if (!videoFmtReceived) {
                            // Buffer it — must add video track first
                            Log.i(TAG, "Audio format received, deferring until video track added")
                            pendingAudioFormat = cmd.format
                        } else {
                            audioTrack.set(muxer.addTrack(cmd.format))
                            Log.i(TAG, "Audio track added: ${audioTrack.get()}")
                            audioFmtReceived = true
                            tryStartMuxer()
                        }
                    }
                    is MuxerCmd.Sample -> {
                        if (!muxerStarted) pendingSamples.add(cmd)
                        else writeSample(cmd)
                    }
                    is MuxerCmd.Stop -> {
                        // Drain any remaining samples before stopping
                        val remaining = mutableListOf<MuxerCmd>()
                        queue.drainTo(remaining)
                        for (r in remaining) {
                            if (r is MuxerCmd.Sample && muxerStarted) writeSample(r)
                        }
                        if (muxerStarted) {
                            try { muxer.stop() } catch (e: Exception) { Log.w(TAG, "muxer.stop: ${e.message}") }
                        }
                        return
                    }
                }
            }
        }

        fun stop(context: Context) {
            if (!stopped.compareAndSet(false, true)) return
            try { videoCodec.signalEndOfInputStream() } catch (e: Exception) { }
            queue.put(MuxerCmd.Stop)
            // Give muxer thread time to flush
            Thread.sleep(300)
            try { muxer.release() } catch (e: Exception) { }
            try { videoCodec.stop(); videoCodec.release() } catch (e: Exception) { }
            try { audioCodec?.stop(); audioCodec?.release() } catch (e: Exception) { }
            try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) { }
            try { pfd.close() } catch (e: Exception) { }
            try {
                val cv = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, cv, null, null)
                Log.i(TAG, "Recording finalised: $uri")
            } catch (e: Exception) { Log.w(TAG, "IS_PENDING clear: ${e.message}") }
        }
    }

    // ─── Session factory ──────────────────────────────────────────────────────

    fun startSession(
        context: Context,
        width: Int           = 1920,
        height: Int          = 1080,
        videoBitrate: Int    = 5_000_000,
        frameRate: Int       = 30,
        rotationDegrees: Int = 0,
        savePath: String     = "DCIM/Camera"
    ): RecordingSession? {
        return try {
            val fileName = "VID_${System.currentTimeMillis()}.mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, savePath)
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI

            val uri = context.contentResolver.insert(collection, cv)
                ?: throw IllegalStateException("MediaStore insert failed")
            val pfd = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("openFileDescriptor failed")

            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotationDegrees != 0) muxer.setOrientationHint(rotationDegrees)
            Log.i(TAG, "Muxer orientation hint: ${rotationDegrees}°")

            val videoFormat = MediaFormat.createVideoFormat(MIME_VIDEO, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val videoCodec = MediaCodec.createEncoderByType(MIME_VIDEO)
            videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = videoCodec.createInputSurface()
            videoCodec.start()
            Log.i(TAG, "AV1 video encoder started: $MIME_VIDEO ${width}x${height}")

            // MP4 muxer only supports AAC audio, not Opus
            val audioMime = MIME_AAC
            val (audioCodec, audioRecord) = buildAudioEncoder(audioMime)

            val session = RecordingSession(
                inputSurface = inputSurface,
                videoCodec   = videoCodec,
                audioCodec   = audioCodec,
                audioRecord  = audioRecord,
                muxer        = muxer,
                pfd          = pfd,
                uri          = uri,
                hasAudio     = (audioCodec != null)
            )

            // Muxer thread — single consumer, owns all muxer calls
            Thread({ session.runMuxerLoop() }, "av1-muxer").apply { isDaemon = true; start() }

            // Encode threads — post commands to queue, never touch muxer
            startVideoDrainThread(session, videoCodec)
            if (audioCodec != null && audioRecord != null)
                startAudioThread(session, audioCodec, audioRecord)

            Log.i(TAG, "Recording session started → $fileName (audio: $audioMime)")
            session
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AV1 session", e)
            null
        }
    }

    // ─── Audio builder ────────────────────────────────────────────────────────

    private fun buildAudioEncoder(mime: String): Pair<MediaCodec?, AudioRecord?> {
        return try {
            val sampleRate  = 48_000
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val encoding    = AudioFormat.ENCODING_PCM_16BIT
            val minBuf      = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRate, channelMask, encoding, minBuf * 4)
            val format = MediaFormat.createAudioFormat(mime, sampleRate, 2).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                if (mime == MIME_AAC) setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            audioRecord.startRecording()
            Log.i(TAG, "Audio encoder started: $mime")
            Pair(codec, audioRecord)
        } catch (e: Exception) {
            Log.w(TAG, "Audio encoder failed, recording without audio: ${e.message}")
            Pair(null, null)
        }
    }

    // ─── Encode threads ───────────────────────────────────────────────────────

    private fun startVideoDrainThread(session: RecordingSession, codec: MediaCodec) {
        Thread({
            val info = MediaCodec.BufferInfo()
            var formatPosted = false
            while (!session.stopped.get()) {
                val idx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!formatPosted) {
                            formatPosted = true
                            session.postVideoFormat(codec.outputFormat)
                        }
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null) session.postVideoSample(buf, info)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(idx, false)
                        if (eos) break
                    }
                }
            }
            Log.i(TAG, "Video drain thread done")
        }, "av1-video-drain").apply { isDaemon = true; start() }
    }

    private fun startAudioThread(session: RecordingSession, codec: MediaCodec, ar: AudioRecord) {
        Thread({
            val pcm  = ByteArray(4096)
            val info = MediaCodec.BufferInfo()
            var formatPosted = false
            while (!session.stopped.get()) {
                val inIdx = codec.dequeueInputBuffer(10_000L)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    inBuf.clear()
                    val read = ar.read(pcm, 0, minOf(pcm.size, inBuf.remaining()))
                    if (read > 0) {
                        inBuf.put(pcm, 0, read)
                        codec.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                    } else {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, 0)
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, 0L)
                while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!formatPosted) {
                                formatPosted = true
                                session.postAudioFormat(codec.outputFormat)
                            }
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)
                            if (buf != null) session.postAudioSample(buf, info)
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                    }
                    outIdx = codec.dequeueOutputBuffer(info, 0L)
                }
            }
            Log.i(TAG, "Audio thread done")
        }, "av1-audio").apply { isDaemon = true; start() }
    }

    fun getStatusDescription(context: Context): String {
        val i = probeAv1Encoder()
        return if (i.available)
            "✓ Hardware AV1: ${i.codecName}\n  Max ${i.maxWidth}x${i.maxHeight}, ${i.maxBitrate/1_000_000}Mbps"
        else "✗ No hardware AV1 encoder (Pixel 6+ required)"
    }
}
