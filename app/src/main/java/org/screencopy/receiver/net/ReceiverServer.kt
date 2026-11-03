package org.screencopy.receiver.net

import android.media.MediaCodecList
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import org.screencopy.receiver.lifecycle.ResourceSlot
import org.screencopy.receiver.media.AccessUnit
import org.screencopy.receiver.media.AudioFormat
import org.screencopy.receiver.media.AudioPlayer
import org.screencopy.receiver.media.PlaybackClock
import org.screencopy.receiver.media.PlaybackException
import org.screencopy.receiver.media.QueueCongestionException
import org.screencopy.receiver.media.QueuePressure
import org.screencopy.receiver.media.AudioOutputPreparationException
import org.screencopy.receiver.media.OutputPreparationDiagnostic
import org.screencopy.receiver.media.VideoPlayer
import org.screencopy.receiver.media.VideoPlaybackException
import org.screencopy.receiver.media.VideoDiagnostic
import org.screencopy.receiver.media.VideoFormat
import org.screencopy.receiver.media.SrtPlayback
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.FailureDiagnostic
import org.screencopy.receiver.ui.FailureOrigin
import org.screencopy.receiver.ui.ReceiverOrientationException
import java.io.EOFException
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ReceiverServer private constructor(
    name: String,
    private val surface: Surface?,
    private val onStatus: (String, String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onListener: (String) -> Unit,
    private val port: Int,
    private val videoOutput: ResourceSlot<VideoPlayer>,
    private val validateRequest: (Request) -> Unit,
    private val onScreenEvent: (ReceiverScreenEvent) -> Unit,
    private val onFailure: (FailureDiagnostic) -> Unit,
    private val onPresentationStart: (ReceiverOrientation) -> AutoCloseable,
    private val videoFactory: ((VideoFormat, PlaybackClock, (String, String) -> Unit, Long) -> VideoPlayer)?,
    private val audioFactory: ((AudioFormat, PlaybackClock, (String, String) -> Unit) -> AudioPlayer)? = null,
    private val clockFactory: () -> PlaybackClock = ::PlaybackClock,
    private val srtOutput: ResourceSlot<SrtPlayback> = ResourceSlot(),
    private val srtFactory: ((Request) -> SrtPlayback)? = null,
    private val validateSrt: () -> Unit = {},
    private val validateSrtCodecs: (Request) -> Unit = {},
    private val onDiscoveryDiagnostic: (String) -> Unit = {}
) : AutoCloseable {
    constructor(
        name: String, surface: Surface,
        onStatus: (String, String) -> Unit, onVideoSize: (Int, Int) -> Unit,
        onListener: (String) -> Unit,
        port: Int = PORT, videoOutput: ResourceSlot<VideoPlayer> = ResourceSlot(),
        onScreenEvent: (ReceiverScreenEvent) -> Unit = {},
        onPresentationStart: (ReceiverOrientation) -> AutoCloseable = {
            if (it != ReceiverOrientation.AUTO) throw ReceiverOrientationException()
            AutoCloseable {}
        },
        srtOutput: ResourceSlot<SrtPlayback> = ResourceSlot(),
        srtFactory: ((Request) -> SrtPlayback)? = null,
        validateSrt: () -> Unit = {
            protocolRequire(org.screencopy.receiver.BuildConfig.SRT_TRIAL, "SRT transport unavailable", "UNSUPPORTED_CONFIG")
        }
    ) : this(name, surface, onStatus, onVideoSize, onListener, port, videoOutput, ::validateCapabilities, onScreenEvent,
        { android.util.Log.e("ScreenCopyReceiver", it.logMessage()) }, onPresentationStart, null,
        srtOutput = srtOutput, srtFactory = srtFactory, validateSrt = validateSrt,
        validateSrtCodecs = ::validateCapabilities,
        onDiscoveryDiagnostic = { android.util.Log.i("ScreenCopyReceiver", it) })

    private val name = name.take(80)
    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val active = AtomicReference<Connection?>()
    val busyForUpdate: Boolean get() = active.get() != null
    private val clients = ConnectionPool<Connection>({ it.abort() })
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private var udp: DatagramSocket? = null
    @Volatile private var networkSignature = ""
    private var ticks = 0
    private val advertisementPackets = AtomicLong()
    private var lastMaintenanceWarning = 0L
    val listeningPort: Int get() = checkNotNull(listener).localPort

    fun start() {
        require(port in 0..65535) { "TCP port must be between 0 and 65535" }
        require(name.isNotEmpty() && name.none { it.code < 32 }) { "Receiver name must be nonempty without control characters" }
        check(started.compareAndSet(false, true)) { "Create a new receiver for each listener startup" }
        running.set(true)
        try {
            listener = ServerSocket()
            checkNotNull(listener).apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 32)
            }
            udp = DatagramSocket().apply { broadcast = true }
            networkSignature = addresses().joinToString()
            acceptThread = Thread({ acceptConnections() }, "ScreenCopy-listener").also { it.start() }
            timer.scheduleWithFixedDelay({ maintenance() }, 0, 1, TimeUnit.SECONDS)
            publishListener()
            available()
            onDiscoveryDiagnostic("event=RECEIVER_LISTENER_STARTED tcpPort=$listeningPort")
        } catch (e: Exception) {
            onDiscoveryDiagnostic("event=RECEIVER_LISTENER_START_FAILED exception=${e.javaClass.simpleName}")
            close()
            throw e
        }
    }

    fun addresses(): List<String> = interfaces().flatMap { item ->
        item.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
    }.sorted()

    fun disconnect(reason: String) { active.get()?.requestStop(reason) }

    private fun acceptConnections() {
        try {
            while (running.get()) {
                val socket = checkNotNull(listener).accept()
                val connection = Connection(socket)
                if (!clients.submit(connection) { connection.run() }) {
                    if (running.get()) onStatus("Connection limit reached", "At most 32 incoming connections are allowed. Wait and retry.")
                }
            }
        } catch (e: IOException) {
            if (running.get()) {
                onStatus("Listener failed", "${e.javaClass.simpleName}: close and reopen receiver to retry")
                onScreenEvent(ReceiverScreenEvent.ListenerStopped)
                onScreenEvent(ReceiverScreenEvent.Problem("LISTENER"))
            }
        }
    }

    private fun maintenance() {
        if (!running.get()) return
        var stage = "SESSIONS"
        try {
            clients.snapshot().forEach { it.tick() }
            if (ticks++ % 2 == 0) {
                stage = "INTERFACES"
                val signature = addresses().joinToString()
                if (signature != networkSignature) {
                    networkSignature = signature
                    disconnect("Receiver network changed; reconnect using its current address")
                    publishListener()
                    if (active.get() == null) available()
                }
                stage = "ADVERTISEMENT"
                advertise()
            }
        } catch (e: Exception) {
            val now = System.nanoTime()
            if (lastMaintenanceWarning == 0L || now - lastMaintenanceWarning >= 10_000_000_000L) {
                onDiscoveryDiagnostic("event=DISCOVERY_MAINTENANCE_FAILED stage=$stage exception=${e.javaClass.simpleName} running=${running.get()}")
                lastMaintenanceWarning = now
            }
            onStatus("Network maintenance error", "${e.message ?: e.javaClass.simpleName}. Manual connection may still be available; reopen receiver if it persists.")
            onScreenEvent(ReceiverScreenEvent.Notice("DISCOVERY"))
        }
    }

    private fun advertise() {
        val targets = interfaces().flatMap { it.interfaceAddresses }.mapNotNull { it.broadcast }.toSet()
        targets.forEach { address -> sendAdvertisement(InetSocketAddress(address, DISCOVERY_PORT)) }
        if (ticks % 10 == 1)
            onDiscoveryDiagnostic("event=DISCOVERY_ADVERTISE targets=${targets.size} destinations=${targets.map { it.hostAddress }.sorted().joinToString(",")} udpPort=$DISCOVERY_PORT packets=${advertisementPackets.get()} busy=${active.get() != null}")
    }

    internal fun sendAdvertisement(target: InetSocketAddress) {
        val bytes = advertisement()
        checkNotNull(udp).send(DatagramPacket(bytes, bytes.size, target))
        advertisementPackets.incrementAndGet()
    }

    internal fun advertisement(): ByteArray {
        val bytes = JSONObject().put("protocol", "screencopy").put("version", Wire.VERSION)
            .put("name", name).put("port", listeningPort)
            .put("state", if (active.get() == null) "available" else "busy").toString().toByteArray(Charsets.UTF_8)
        check(bytes.size <= 1024) { "Discovery advertisement exceeds 1024 bytes" }
        return bytes
    }

    private fun publishListener() {
        if (!running.get()) return
        val endpoints = addresses().map { "$it:$listeningPort" }
        if (running.get()) onScreenEvent(ReceiverScreenEvent.Listener(endpoints))
        if (running.get()) onListener("$name\n" +
            endpoints.joinToString("\n").ifEmpty { "No local IPv4 network; connect Wi-Fi or Ethernet (TCP $listeningPort)" } +
            "\nUnencrypted trusted-LAN TCP.")
    }

    private fun available() {
        if (running.get()) {
            onStatus("Available", "Select this screen's IP address on the sender. Surface ready; audio starts only when requested.")
            onScreenEvent(ReceiverScreenEvent.CleanupFinished)
        }
    }

    override fun close() {
        running.set(false)
        onDiscoveryDiagnostic("event=RECEIVER_LISTENER_STOPPED")
        active.get()?.noteClose(org.screencopy.receiver.media.SrtCloseOwner.SURFACE_RETIRED)
        timer.shutdownNow()
        val errors = mutableListOf<Exception>()
        fun cleanup(action: () -> Unit) {
            try { action() } catch (e: Exception) { errors.add(e) }
        }
        cleanup { listener?.close() }
        cleanup { udp?.close() }
        cleanup { clients.close() }
        cleanup {
            acceptThread?.join(2000)
            check(acceptThread?.isAlive != true) { "Listener thread did not terminate after socket close" }
        }
        cleanup { check(clients.awaitTermination(5, TimeUnit.SECONDS)) { "Session cleanup timed out; reopen the app before receiving" } }
        cleanup { check(timer.awaitTermination(2, TimeUnit.SECONDS)) { "Network cleanup timed out" } }
        if (errors.isNotEmpty()) throw IllegalStateException("Receiver cleanup failed; reopen the app", errors.first()).also {
            errors.drop(1).forEach(it::addSuppressed)
        }
    }

    private inner class Connection(private val socket: Socket) {
        @Volatile private var writer: ControlWriter? = null
        @Volatile private var lastRead = now()
        @Volatile private var lastWrite = now()
        @Volatile private var phaseAt = now()
        @Volatile private var phase = "Setup"
        @Volatile private var session = 0L
        private var senderName = ""
        private val stopping = AtomicBoolean(false)
        @Volatile private var stopAt = 0L
        private val playbackError = AtomicReference<String?>()
        private val firstFailure = AtomicReference<FailureDiagnostic?>()
        private val connectionError = AtomicReference<Pair<String, String>?>()
        private val playbackLock = Any()
        private val clock by lazy(clockFactory)
        @Volatile private var srt: SrtPlayback? = null
        private var srtWidth = 0
        private var srtHeight = 0
        private var video: VideoPlayer? = null
        private var audio: AudioPlayer? = null
        private var presentation: AutoCloseable? = null
        private var request: Request? = null
        private var tracks: TrackState? = null
        private var requestSummary = ""
        private var actualVideo = ""
        private var actualAudio = "Audio disabled explicitly"
        private var decoderDescription = ""
        private var terminalError = false
        private var initializingTrack = 0
        private val framePresented = AtomicBoolean(false)
        @Volatile private var tracksReady = false
        @Volatile private var videoGeneration = 0L

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 10_000
                writer = ControlWriter(socket, { lastWrite = now(); srt?.controlProgress(false) }, { detail ->
                    connectionError.compareAndSet(null, "INTERNAL" to detail)
                    srt?.controlFailure(IOException("SRT control write failed"))
                    noteClose(org.screencopy.receiver.media.SrtCloseOwner.ERROR)
                    socket.close()
                })
                val hello = read()
                protocolRequire(hello.type == Type.HELLO && hello.session == 0L, "Expected sender HELLO")
                val h = hello.json()
                protocolRequire(h.string("role") == "sender", "Unexpected peer role")
                val versions = h.array("versions")
                protocolRequire(versions.length() in 1..16 && (0 until versions.length()).all { versions.opt(it) is Int }, "Invalid version list")
                protocolRequire((0 until versions.length()).any { versions.opt(it) == Wire.VERSION }, "No compatible protocol version", "VERSION")
                senderName = h.string("name", 80)
                protocolRequire(senderName.isNotEmpty(), "Sender name is empty")
                send(Type.HELLO, JSONObject().put("role", "receiver").put("versions", JSONArray().put(Wire.VERSION))
                    .put("name", name))
                phase = "Negotiating"
                phaseAt = now()
                val negotiation = readControlUntilRequest()
                session = negotiation.session
                protocolRequire(session > 0, "Session ID must be positive")
                if (!active.compareAndSet(null, this)) {
                    send(Type.REJECT, JSONObject().put("code", "BUSY").put("message", "Receiver already has an active sender"))
                    return
                }
                onScreenEvent(ReceiverScreenEvent.Connecting)
                request = Request.parse(negotiation.json())
                val settings = checkNotNull(request)
                settings.mediaTransport?.validatePeer(checkNotNull(socket.inetAddress.hostAddress))
                (settings.mediaTransport as? SrtTransport)?.validateSession(session)
                if (settings.mediaTransport == null) validateRequest(settings)
                else { validateSrt(); validateSrtCodecs(settings) }
                requestSummary = settings.summary
                if (settings.mediaTransport == null) {
                    tracks = TrackState(settings.tracks)
                    clock.audioExpected = settings.audioCodec != null
                }
                phase = "Initializing tracks"
                phaseAt = now()
                onStatus(phase, "$senderName\n$requestSummary")
                val accepted = JSONObject().put("tracks", JSONArray(settings.tracks))
                if (settings.mediaTransport != null) accepted.put("mediaTransport", JSONObject().put("kind", settings.mediaTransport.kind))
                send(Type.SESSION_ACCEPT, accepted)
                if (settings.mediaTransport != null) synchronized(playbackLock) {
                    srt = srtOutput.replace {
                        (srtFactory?.invoke(settings)
                            ?: throw ProtocolException("UNSUPPORTED_CONFIG", "SRT player factory unavailable")).also { player ->
                            try { player.start() } catch (e: Throwable) { player.close(); throw e }
                        }
                    }
                }
                while (running.get() && !socket.isClosed) {
                    val message = read()
                    srt?.controlProgress(true)
                    srt?.sessionPhase(phase)
                    protocolRequire(message.session == session, "Session ID changed")
                    if (message.type == Type.SESSION_END) {
                        protocolRequire(stopping.get(), "Unexpected SESSION_END")
                        message.json().string("reason", 1024)
                        return
                    }
                    if (stopping.get()) continue
                    when (message.type) {
                        Type.STREAM_CONFIG -> {
                            protocolRequire(settings.mediaTransport == null, "Custom track config forbidden in external media session")
                            configure(message)
                        }
                        Type.VIDEO_FRAME, Type.AUDIO_PACKET -> synchronized(playbackLock) {
                            protocolRequire(settings.mediaTransport == null, "Custom media forbidden in external media session")
                            checkNotNull(tracks).media(message)
                            val packet = AccessUnit(message.ptsUs, message.payload, message.flags == 1)
                            if (message.type == Type.VIDEO_FRAME) {
                                clock.anchorVideo(packet.ptsUs)
                                if (settings.videoCodec != "av1") protocolRequire(Configuration.annexB(message.payload), "Expected Annex B video access unit")
                                checkNotNull(video).offer(packet)
                            } else checkNotNull(audio).offer(packet)
                        }
                        Type.PING -> send(Type.PONG, JSONObject().put("nonce", message.json().integer("nonce")))
                        Type.PONG -> message.json().integer("nonce")
                        Type.STATUS -> {
                            val json = message.json()
                            json.string("state", 80)
                            json.string("message", 2048)
                        }
                        Type.ERROR -> {
                            val json = message.json()
                            val code = json.string("code", 80)
                            val detail = json.string("message", 2048)
                            protocolRequire(code in ERROR_CODES, "Unknown sender error code")
                            if (json.boolean("fatal")) {
                                reportFailure(code, detail, FailureOrigin.PEER)
                                throw ProtocolException(code, "Sender: $detail")
                            }
                            onStatus("Sender warning: $code", detail)
                        }
                        Type.STOP -> {
                            val reason = message.json().string("reason", 1024)
                            stopPlayback(org.screencopy.receiver.media.SrtCloseOwner.REMOTE_STOP)
                            send(Type.SESSION_END, JSONObject().put("reason", reason))
                            return
                        }
                        else -> throw ProtocolException("PROTOCOL", "Message is not allowed in current session state")
                    }
                }
            } catch (e: Exception) {
                srt?.controlFailure(e)
                terminalError = true
                if (running.get() && !stopping.get() && playbackError.get() == null) {
                    val externalError = connectionError.get()
                    val code = externalError?.first ?: when (e) {
                        is ProtocolException -> e.code
                        is PlaybackException -> e.code
                        is java.net.SocketTimeoutException -> "TIMEOUT"
                        is EOFException, is SocketException -> "PROTOCOL"
                        else -> if (phase == "Initializing tracks") "UNSUPPORTED_CONFIG" else "INTERNAL"
                    }
                    val detail = externalError?.second ?: when {
                        e is EOFException -> "Sender disconnected or truncated a message"
                        else -> e.message ?: e.javaClass.simpleName
                    }
                    try {
                        send(if (phase == "Negotiating" && session > 0) Type.REJECT else Type.ERROR,
                            JSONObject().put("code", code).put("message", detail).put("fatal", true))
                    } catch (sendError: Exception) {
                        if (active.get() === this) onStatus("Connection closed", "$detail; could not deliver error (${sendError.javaClass.simpleName})")
                    }
                    if (active.get() === this || active.get() == null) onStatus("Session error: $code", "$detail\nReconnect after correcting the sender settings or network.")
                    if (active.get() === this) reportFailure(code, detail,
                        when {
                            e is ReceiverOrientationException -> FailureOrigin.PRESENTATION
                            phase == "Initializing tracks" && initializingTrack == 2 -> FailureOrigin.AUDIO
                            phase == "Initializing tracks" && initializingTrack == 1 -> FailureOrigin.VIDEO
                            else -> FailureOrigin.SESSION
                        },
                        (e as? QueueCongestionException)?.pressure, (e as? AudioOutputPreparationException)?.diagnostic,
                        (e as? VideoPlaybackException)?.diagnostic)
                }
            } finally {
                var cleanupFailed = false
                try {
                    stopPlayback()
                } catch (e: Exception) {
                    cleanupFailed = true
                    onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                    reportFailure("INTERNAL", "Playback cleanup failed", FailureOrigin.CLEANUP)
                } finally {
                    try {
                        writer?.close()
                    } catch (e: Exception) {
                        cleanupFailed = true
                        onStatus("Connection cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                        reportFailure("INTERNAL", "Connection cleanup failed", FailureOrigin.CLEANUP)
                    } finally {
                        try { socket.close() } finally {
                            if (active.compareAndSet(this, null) && running.get()) {
                                if (!cleanupFailed) available()
                            }
                        }
                    }
                }
            }
        }

        private fun read(): Message = Wire.read(socket.inputStream).also { lastRead = now() }

        private fun readControlUntilRequest(): Message {
            while (true) {
                val message = read()
                if (message.type == Type.SESSION_REQUEST) return message
                protocolRequire(message.session == 0L, "Unexpected session before negotiation")
                when (message.type) {
                    Type.PING -> send(Type.PONG, JSONObject().put("nonce", message.json().integer("nonce")))
                    Type.PONG -> message.json().integer("nonce")
                    else -> throw ProtocolException("PROTOCOL", "Expected SESSION_REQUEST")
                }
            }
        }

        private fun configure(message: Message) = synchronized(playbackLock) {
            phase = "Initializing tracks"
            initializingTrack = message.track
            phaseAt = now()
            val trackState = checkNotNull(tracks)
            val settings = checkNotNull(request)
            val json = message.json()
            val codec = if (message.track == 2) settings.audioCodec else settings.videoCodec
            trackState.configure(message.track, message.generation, codec?.takeIf { json.opt("codec") == it })
            if (message.track == 1) {
                if (message.generation > 1) clock.endVideoPreroll()
                val format = Configuration.video(json, settings)
                actualVideo = "Actual video: ${format.mime} ${format.width}x${format.height}, generation ${message.generation}"
                video?.let { videoOutput.release(it) }
                video = null
                videoGeneration = message.generation
                video = videoOutput.replace {
                    val failed: (String, String) -> Unit = { code, detail -> playbackFailed(code, detail, FailureOrigin.VIDEO) }
                    (videoFactory?.invoke(format, clock, failed, message.generation) ?:
                    VideoPlayer(format, checkNotNull(surface) { "Playback Surface is unavailable" }, clock, { diagnostic ->
                        decoderDescription = diagnostic
                        onStatus("Initializing video", "$senderName\n$requestSummary\nActual: ${format.width}x${format.height}; $diagnostic")
                    }, failed, {
                        if (running.get() && !stopping.get() && active.get() === this &&
                            videoGeneration == message.generation && tracksReady &&
                            framePresented.compareAndSet(false, true)) {
                            onScreenEvent(ReceiverScreenEvent.FramePresented)
                        }
                    }, generation = message.generation)).also { it.start() }
                }
                onVideoSize(format.width, format.height)
            } else {
                val format = Configuration.audio(json, settings)
                audio?.close()
                audio = null
                val failed: (String, String) -> Unit = { code, detail -> playbackFailed(code, detail, FailureOrigin.AUDIO) }
                audio = (audioFactory?.invoke(format, clock, failed) ?: AudioPlayer(format, clock, failed,
                    onClockCorrection = { android.util.Log.i("ScreenCopyReceiver", it.logMessage()) },
                    onOutputTiming = { android.util.Log.i("ScreenCopyReceiver", "event=AUDIO_OUTPUT_CLOCK stage=RATE_CORRECTION ${it.logMessage()}") },
                    onUnderrunRecovery = { android.util.Log.w("ScreenCopyReceiver", it.logMessage()) })).also { it.start() }
                actualAudio = "Actual audio: ${format.codec} ${format.sampleRate} Hz ${format.channels}ch, pre-skip ${format.preSkip}, generation ${message.generation}\n${checkNotNull(audio).decoderDescription}"
            }
            trackState.ready(message.track)
            send(Type.STREAM_READY, JSONObject().put("ok", true), message.track, message.generation)
            if (trackState.allReady()) {
                if (presentation == null) presentation = onPresentationStart(settings.receiverOrientation)
                tracksReady = true
                phase = "Playing"
                onStatus("Playing: $senderName", "$requestSummary\n$actualVideo\n$decoderDescription\n$actualAudio\nSurface ready; bounded synchronized playback active.")
            }
        }

        private fun send(type: Type, json: JSONObject, track: Int = 0, generation: Long = 0) {
            checkNotNull(writer) { "TCP writer is not available" }.offer(Message.json(type, json, session, track, generation))
        }

        private fun reportFailure(code: String, detail: String, origin: FailureOrigin, pressure: QueuePressure? = null,
            outputPreparation: OutputPreparationDiagnostic? = null, videoDiagnostic: VideoDiagnostic? = null) {
            val diagnostic = FailureDiagnostic.classify(code, origin, phase, detail, pressure, outputPreparation, videoDiagnostic)
            if (!firstFailure.compareAndSet(null, diagnostic)) return
            onFailure(diagnostic)
            onScreenEvent(ReceiverScreenEvent.Problem(code, diagnostic.reason, diagnostic.pressure))
        }

        private fun playbackFailed(code: String, reason: String, origin: FailureOrigin) {
            if (!playbackError.compareAndSet(null, reason)) return
            onStatus("Playback failed", "$reason\nReconnect after reducing sender load or fixing the output device.")
            if (active.get() === this) reportFailure(code, reason, if (code == "AUDIO_FAILED") FailureOrigin.AUDIO else origin)
            try {
                send(Type.ERROR, JSONObject().put("code", code).put("message", reason).put("fatal", true))
            } catch (e: Exception) {
                onStatus("Playback failed", "$reason; could not notify sender (${e.javaClass.simpleName})")
            } finally {
                try { socket.shutdownInput() } catch (_: IOException) { socket.close() }
            }
        }

        fun requestStop(reason: String) {
            stopAt = now()
            if (!stopping.compareAndSet(false, true)) return
            noteClose(org.screencopy.receiver.media.SrtCloseOwner.USER_STOP)
            try {
                send(Type.STOP, JSONObject().put("reason", reason))
            } catch (e: Exception) {
                onStatus("Disconnect error", "${e.message ?: e.javaClass.simpleName}; connection closed")
                socket.close()
            } finally {
                try {
                    stopPlayback(org.screencopy.receiver.media.SrtCloseOwner.USER_STOP)
                } catch (e: Exception) {
                    onStatus("Playback cleanup failed", "${e.message ?: e.javaClass.simpleName}; reopen receiver")
                    socket.close()
                }
            }
        }

        private fun stopPlayback(owner: org.screencopy.receiver.media.SrtCloseOwner = org.screencopy.receiver.media.SrtCloseOwner.ERROR) = synchronized(playbackLock) {
            tracksReady = false
            val oldVideo = video
            val oldAudio = audio
            val oldPresentation = presentation
            val oldSrt = srt
            srt = null
            video = null
            audio = null
            presentation = null
            try {
                try { oldVideo?.halt() } finally { oldAudio?.halt() }
            } finally {
                try { oldVideo?.let { videoOutput.release(it) } } finally {
                    try { oldAudio?.close() } finally {
                        try { oldSrt?.let {
                            try { it.closeInitiated(owner) } finally { srtOutput.release(it) }
                        } } finally { oldPresentation?.close() }
                    }
                }
            }
        }

        fun tick() {
            val player = srt
            player?.sessionPhase(phase)
            if (player != null && !stopping.get() && !srtOutput.isClosed && active.get() === this) {
                try {
                    val result = player.poll()
                    if (result.startsWith("ERROR:")) {
                        srtFailed(result.removePrefix("ERROR:"))
                    } else if (result.isNotEmpty() &&
                        JSONObject(result).let { details ->
                            details.getBoolean("firstFrame") &&
                                (request?.audioCodec == null || details.getBoolean("audioProof"))
                        }) {
                        val details = JSONObject(result)
                        protocolRequire(details.getInt("width") > 0 && details.getInt("height") > 0 &&
                            details.getString("decoder").isNotEmpty(), "Invalid library playback diagnostics")
                        val lease = if (phase != "Playing") onPresentationStart(checkNotNull(request).receiverOrientation) else null
                        synchronized(playbackLock) {
                            if (srt !== player || stopping.get() || srtOutput.isClosed ||
                                !running.get() || active.get() !== this) lease?.close()
                            else {
                                val width = details.getInt("width")
                                val height = details.getInt("height")
                                if (width != srtWidth || height != srtHeight) {
                                    srtWidth = width
                                    srtHeight = height
                                    onVideoSize(width, height)
                                }

                                if (lease != null) {
                                    presentation = lease
                                    tracksReady = true
                                    phase = "Playing"
                                    onStatus("Playing: $senderName", details.getString("decoder"))
                                    send(Type.STATUS, JSONObject().put("state", "srt-playing")
                                        .put("message", "Media3 first video frame and enabled audio advancing"))
                                    onScreenEvent(ReceiverScreenEvent.FramePresented)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    player.controlFailure(e)
                    srtFailed(e.message ?: "SRT playback failed", e is ReceiverOrientationException)
                }
            }
            val time = now()
            when {
                stopping.get() && time - stopAt >= 2000 -> socket.close()
                time - lastRead >= 10_000 || phase != "Playing" && !stopping.get() && time - phaseAt >= 10_000 -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "No progress from sender within 10 seconds")
                    player?.controlFailure(IOException("SRT control timeout"))
                    noteClose(org.screencopy.receiver.media.SrtCloseOwner.ERROR)
                    if (active.get() === this) onStatus("Session timed out", "No progress from sender within 10 seconds. Check its network and retry.")
                    socket.close()
                }
                writer?.stalled(time) == true -> {
                    connectionError.compareAndSet(null, "TIMEOUT" to "Control write blocked for 10 seconds")
                    player?.controlFailure(IOException("SRT control write failed"))
                    noteClose(org.screencopy.receiver.media.SrtCloseOwner.ERROR)
                    if (active.get() === this) onStatus("Connection stalled", "Control write blocked for 10 seconds; reconnect")
                    socket.close()
                }
                writer != null && phase != "Setup" && time - lastWrite >= 2000 -> {
                    send(Type.PING, JSONObject().put("nonce", (time % Int.MAX_VALUE).toInt()))
                }
            }
        }

        fun noteClose(owner: org.screencopy.receiver.media.SrtCloseOwner) {
            srt?.closeInitiated(owner)
        }

        private fun srtFailed(detail: String, presentationError: Boolean = false) {
            playbackFailed(if (presentationError) "UNSUPPORTED_CONFIG" else "DECODER_FAILED", detail,
                if (presentationError) FailureOrigin.PRESENTATION else FailureOrigin.VIDEO)
            // A playback error must retire the Surface independently of a blocked control read.
            try { stopPlayback() } finally {
                try { writer?.close() } finally { socket.close() }
            }
        }

        fun abort() { stopping.set(true); socket.close() }
    }

    companion object {
        const val PORT = 27184
        const val DISCOVERY_PORT = 27183
        const val DEFAULT_NAME = "ScrcpyReceive"
        private val ERROR_CODES = setOf("VERSION", "BUSY", "UNSUPPORTED_CONFIG", "PROTOCOL",
            "CAPTURE_FAILED", "AUDIO_FAILED", "DECODER_FAILED", "TIMEOUT", "CONGESTION", "INTERNAL")
        private fun now() = System.nanoTime() / 1_000_000
        internal fun forProtocolTests(name: String, onStatus: (String, String) -> Unit = { _, _ -> },
            onListener: (String) -> Unit = {}, validateRequest: (Request) -> Unit = {},
            onScreenEvent: (ReceiverScreenEvent) -> Unit = {}, port: Int = 0,
            onFailure: (FailureDiagnostic) -> Unit = {},
            onPresentationStart: (ReceiverOrientation) -> AutoCloseable = { AutoCloseable {} },
            videoFactory: ((VideoFormat, PlaybackClock, (String, String) -> Unit, Long) -> VideoPlayer)? = null,
            audioFactory: ((AudioFormat, PlaybackClock, (String, String) -> Unit) -> AudioPlayer)? = null,
            clockFactory: () -> PlaybackClock = ::PlaybackClock,
            srtFactory: ((Request) -> SrtPlayback)? = null,
            srtOutput: ResourceSlot<SrtPlayback> = ResourceSlot(),
            onVideoSize: (Int, Int) -> Unit = { _, _ -> },
            validateSrtCodecs: (Request) -> Unit = {}) =
            ReceiverServer(name, null, onStatus, onVideoSize, onListener, port, ResourceSlot(), validateRequest, onScreenEvent,
                onFailure, onPresentationStart, videoFactory, audioFactory, clockFactory, srtOutput,
                srtFactory = srtFactory, validateSrtCodecs = validateSrtCodecs)

        private fun validateCapabilities(settings: Request) {
            val types = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }.flatMap { it.supportedTypes.toList() }
            protocolRequire(types.any { it.equals(Configuration.mime(settings.videoCodec), true) }, "No decoder for selected video codec", "UNSUPPORTED_CONFIG")
            if (settings.audioCodec == "aac") protocolRequire(types.any { it.equals("audio/mp4a-latm", true) }, "No AAC decoder", "UNSUPPORTED_CONFIG")
            if (settings.audioCodec == "opus") {
                if (settings.mediaTransport is SrtTransport) {
                    protocolRequire(types.any { it.equals("audio/opus", true) }, "No platform Opus decoder for Media3 SRT", "UNSUPPORTED_CONFIG")
                    return
                }
                try {
                    org.screencopy.receiver.media.OpusDecoder(2).use { }
                } catch (_: LinkageError) {
                    throw ProtocolException("UNSUPPORTED_CONFIG", "Bundled Opus decoder could not load for this device ABI")
                }
            }
        }
        private fun interfaces(): List<NetworkInterface> =
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp && !it.isLoopback }
    }
}
