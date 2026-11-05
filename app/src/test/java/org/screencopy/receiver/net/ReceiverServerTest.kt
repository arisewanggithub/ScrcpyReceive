package org.screencopy.receiver.net

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.screencopy.receiver.protocol.*
import org.screencopy.receiver.ui.ReceiverScreen
import org.screencopy.receiver.ui.ReceiverScreenEvent
import org.screencopy.receiver.ui.ScreenPhase
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReceiverServerTest {
    @Test fun nonzeroRotationBitsAreRejectedWithoutNegotiationBeforeTrackAdmission() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                Wire.write(peer.outputStream, Message(Type.VIDEO_FRAME, 77, 1, 1, 1_000_000, 3,
                    byteArrayOf(0, 0, 0, 1, 0x65)))
                val error = read(peer)
                assertEquals(Type.ERROR, error.type)
                assertEquals("PROTOCOL", error.json().string("code"))
                assertEquals("Video rotation was not negotiated", error.json().string("message"))
            }
        }
    }

    @Test fun rotationCapabilityIsOptInAndMalformedRequestsFailBeforeNegotiation() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            for (capability in listOf(false, true, "true", JSONObject.NULL)) {
                connect(server).use { peer ->
                    send(peer, Type.HELLO, JSONObject().put("role", "sender").put("versions", JSONArray().put(3))
                        .put("name", "Phone").put("videoRotation", capability))
                    val response = read(peer)
                    if (capability is Boolean) {
                        assertEquals(Type.HELLO, response.type)
                        assertEquals(capability, response.json().opt("videoRotation") == true)
                    } else {
                        assertEquals(Type.ERROR, response.type)
                        assertEquals("PROTOCOL", response.json().getString("code"))
                    }
                }
            }
        }
    }

    @Test fun externalTransportDescriptorsAreRejectedBeforeAcceptanceAndCannotFallBackToTcp() {
        val validated = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", validateRequest = { validated.incrementAndGet() }).use { server ->
            server.start()
            val descriptors = listOf(JSONObject().put("kind", "external"), JSONObject(), JSONObject.NULL, "external", false)
            descriptors.forEachIndexed { index, descriptor ->
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings().put("mediaTransport", descriptor), index + 1L)
                    val rejection = read(peer)
                    assertEquals(Type.REJECT, rejection.type)
                    assertEquals("UNSUPPORTED_CONFIG", rejection.json().string("code"))
                    assertEquals(-1, peer.inputStream.read())
                }
                await { !server.busyForUpdate }
            }
            assertEquals("An external transport must not reach codec validation or acceptance", 0, validated.get())
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 99)
                val accepted = read(peer)
                assertEquals(Type.SESSION_ACCEPT, accepted.type)
                assertFalse(accepted.json().has("mediaTransport"))
                assertEquals(1, validated.get())
                send(peer, Type.STOP, JSONObject().put("reason", "done"), 99)
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
    }

    @Test fun listenerScreenShowsActualBoundPortAndRenamedDiscoveryLabel() {
        val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
        val descriptions = ConcurrentLinkedQueue<String>()
        ReceiverServer.forProtocolTests(ReceiverServer.DEFAULT_NAME,
            onListener = { descriptions.add(it) }, onScreenEvent = { events.add(it) }).use { server ->
            server.start()
            val listener = events.filterIsInstance<ReceiverScreenEvent.Listener>().first()
            val expected = server.addresses().map { "$it:${server.listeningPort}" }
            assertEquals(expected, listener.addresses)
            assertEquals(server.addresses().joinToString("\n") { "$it : ${server.listeningPort}" },
                ReceiverScreen().reduce(listener).addressText)
            assertTrue(server.listeningPort in 1..65535)
            assertTrue(expected.all { descriptions.first().contains(it) })
            assertEquals("ScrcpyReceive", StrictJson.decode(server.advertisement()).string("name"))
        }
    }

    @Test fun actualCodeFreeHelloAndMultipleCastsUseIndependentPositiveSessionIds() {
        val available = AtomicInteger()
        ReceiverServer.forProtocolTests("Receiver", { state, _ -> if (state == "Available") available.incrementAndGet() }).use { server ->
            server.start()
            val port = server.listeningPort
            for (session in 1L..2L) {
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), session)
                    val accept = read(peer)
                    assertEquals(Type.SESSION_ACCEPT, accept.type)
                    assertEquals(session, accept.session)
                    assertEquals(listOf(1), accept.json().array("tracks").let { array -> (0 until array.length()).map(array::getInt) })
                    send(peer, Type.STOP, JSONObject().put("reason", "user"), session)
                    val end = read(peer)
                    assertEquals(Type.SESSION_END, end.type)
                    assertEquals("user", end.json().string("reason"))
                    assertEquals(-1, peer.inputStream.read())
                }
                await { available.get() >= session.toInt() + 1 }
                assertEquals(port, server.listeningPort)
            }
        }
    }

    @Test fun sameEndpointCanReconnectAfterListenerRestartWithoutHumanIdentity() {
        val first = ReceiverServer.forProtocolTests("Receiver")
        val port = first.use { server ->
            server.start()
            connect(server).use { hello(it) }
            server.listeningPort
        }
        ReceiverServer.forProtocolTests("Renamed screen", port = port).use { server ->
            server.start()
            connect(server).use { peer ->
                val response = hello(peer)
                assertEquals("Renamed screen", response.json().string("name"))
                send(peer, Type.SESSION_REQUEST, settings(), 19)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
            }
        }
    }

    @Test fun obsoleteHumanCodeFieldsAreIgnoredRatherThanValidatedOrReflected() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            for (oldValue in listOf<Any>(123, true, "not-six-digits", JSONObject.NULL, JSONObject().put("arbitrary", 1))) {
                connect(server).use { peer ->
                    val response = hello(peer, helloPayload().put("code", oldValue).put("sessionCode", oldValue))
                    assertFalse(response.json().has("code"))
                    assertFalse(response.json().has("sessionCode"))
                }
            }
        }
    }

    @Test fun busyCannotReplaceActiveCastAndOwnerCanContinueWithoutAnyCode() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { owner ->
                hello(owner)
                send(owner, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.SESSION_ACCEPT, read(owner).type)
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), 78)
                    val busy = read(peer)
                    assertEquals(Type.REJECT, busy.type)
                    assertEquals(78L, busy.session)
                    assertEquals("BUSY", busy.json().string("code"))
                }
                send(owner, Type.PING, JSONObject().put("nonce", 42), 77)
                assertEquals(42, read(owner).json().integer("nonce"))
                assertEquals("busy", StrictJson.decode(server.advertisement()).string("state"))
                send(owner, Type.STOP, JSONObject().put("reason", "done"), 77)
                assertEquals(Type.SESSION_END, read(owner).type)
            }
        }
    }

    @Test fun simultaneousRequestsAcceptExactlyOneSender() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            val barrier = CyclicBarrier(2)
            val threads = Executors.newFixedThreadPool(2)
            val peers = listOf(connect(server), connect(server))
            try {
                val results = peers.mapIndexed { index, peer -> threads.submit<Message> {
                    hello(peer)
                    barrier.await(3, TimeUnit.SECONDS)
                    send(peer, Type.SESSION_REQUEST, settings(), index + 1L)
                    read(peer)
                } }.map { it.get(3, TimeUnit.SECONDS) }
                assertEquals(1, results.count { it.type == Type.SESSION_ACCEPT })
                assertEquals(1, results.count { it.type == Type.REJECT && it.json().string("code") == "BUSY" })
            } finally {
                peers.forEach(Socket::close)
                threads.shutdownNow()
                assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun actualDiscoveryDatagramContainsOnlyNamePortVersionAndState() {
        ReceiverServer.forProtocolTests("Family screen").use { server ->
            server.start()
            DatagramSocket(InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).use { receiver ->
                receiver.soTimeout = 1000
                server.sendAdvertisement(InetSocketAddress(InetAddress.getLoopbackAddress(), receiver.localPort))
                val packet = DatagramPacket(ByteArray(1024), 1024)
                receiver.receive(packet)
                val advertisement = StrictJson.decode(packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
                assertTrue(packet.address.isLoopbackAddress)
                assertEquals(setOf("protocol", "version", "name", "port", "state"), advertisement.keys().asSequence().toSet())
                assertEquals("screencopy", advertisement.string("protocol"))
                assertEquals(3, advertisement.integer("version"))
                assertEquals("Family screen", advertisement.string("name"))
                assertEquals(server.listeningPort, advertisement.integer("port"))
                assertEquals("available", advertisement.string("state"))
            }
        }
    }

    @Test fun tlsV1V2AndReservedAuthFailBeforeTheRestOfTheHeader() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            val prefixes = listOf(byteArrayOf(0x16, 0x03, 0x01, 0x00) to "PROTOCOL",
                hex("534350590001") to "VERSION", hex("534350590002") to "VERSION",
                hex("5343505900030002") to "PROTOCOL")
            prefixes.forEach { (prefix, code) ->
                connect(server).use { peer ->
                    peer.outputStream.write(prefix)
                    peer.outputStream.flush()
                    val error = read(peer)
                    assertEquals(Type.ERROR, error.type)
                    assertEquals(code, error.json().string("code"))
                    assertEquals(-1, peer.inputStream.read())
                }
            }
        }
    }

    @Test fun helloStillValidatesRoleVersionAndNonemptyName() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            listOf(helloPayload().put("role", "receiver"), helloPayload().put("name", ""),
                helloPayload().put("name", 7), helloPayload().put("name", "x".repeat(81)),
                helloPayload().put("versions", JSONArray().put(2))).forEach { invalid ->
                connect(server).use { peer ->
                    send(peer, Type.HELLO, invalid)
                    assertEquals(Type.ERROR, read(peer).type)
                }
            }
        }
    }

    @Test fun internalSessionIdIsStillRequiredAndRequestCannotPrecedeHello() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 0)
                assertEquals("PROTOCOL", read(peer).json().string("code"))
            }
            connect(server).use { peer ->
                send(peer, Type.SESSION_REQUEST, settings(), 1)
                assertEquals("PROTOCOL", read(peer).json().string("code"))
            }
        }
    }

    @Test fun fragmentedHelloAndCoalescedRequestPingAndStopUseTheRealReader() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                val encoded = ByteArrayOutputStream().also { Wire.write(it, Message.json(Type.HELLO, helloPayload())) }.toByteArray()
                encoded.forEach { peer.outputStream.write(it.toInt() and 255) }
                peer.outputStream.flush()
                assertEquals(Type.HELLO, read(peer).type)
                val coalesced = ByteArrayOutputStream().also {
                    Wire.write(it, Message.json(Type.SESSION_REQUEST, settings(), 23))
                    Wire.write(it, Message.json(Type.PING, JSONObject().put("nonce", 7), 23))
                    Wire.write(it, Message.json(Type.STOP, JSONObject().put("reason", "coalesced"), 23))
                }.toByteArray()
                peer.outputStream.write(coalesced)
                peer.outputStream.flush()
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                assertEquals(Type.PONG, read(peer).type)
                assertEquals(Type.SESSION_END, read(peer).type)
            }
        }
    }

    @Test fun closeAbortsIdleSetupConnectionsAndAllowsImmediatePortRebind() {
        val server = ReceiverServer.forProtocolTests("Receiver")
        server.start()
        val port = server.listeningPort
        val peers = (1..16).map { connect(server) }
        try {
            server.close()
            peers.forEach { peer ->
                assertFalse(peer.isClosed)
                try {
                    assertEquals(-1, peer.inputStream.read())
                } catch (e: SocketException) {
                    // Closing a listener's accepted/backlogged peers may produce reset instead of FIN.
                    if (!e.message.equals("Connection reset", ignoreCase = true) &&
                        !e.message.equals("Connection reset by peer", ignoreCase = true)) throw e
                }
            }
            java.net.ServerSocket().use { it.reuseAddress = true; it.bind(InetSocketAddress(port)) }
            ReceiverServer.forProtocolTests("Rebound receiver", port = port).use { rebound ->
                rebound.start()
                assertEquals(port, rebound.listeningPort)
                connect(rebound).use { peer ->
                    assertEquals("Rebound receiver", hello(peer).json().string("name"))
                }
            }
            assertThrows(IllegalStateException::class.java) { server.start() }
        } finally { peers.forEach(Socket::close); server.close() }
    }

    @Test fun unsupportedRequestedSettingsProduceExplicitTerminalRejection() {
        ReceiverServer.forProtocolTests("Receiver", validateRequest = { throw ProtocolException("UNSUPPORTED_CONFIG", "Selected codec unavailable") }).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 22)
                val rejection = read(peer)
                assertEquals(Type.REJECT, rejection.type)
                assertEquals("UNSUPPORTED_CONFIG", rejection.json().string("code"))
                assertEquals(22L, rejection.session)
            }
        }
    }

    @Test fun senderStopAndLostConnectionReturnReadyWithCurrentEndpointsAfterCleanup() {
        for (orderly in listOf(true, false)) {
            val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
            ReceiverServer.forProtocolTests("Receiver", onScreenEvent = events::add).use { server ->
                server.start()
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), 90)
                    assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                    assertTrue(events.contains(ReceiverScreenEvent.Connecting))
                    assertFalse(events.contains(ReceiverScreenEvent.FramePresented))
                    if (orderly) {
                        send(peer, Type.STOP, JSONObject().put("reason", "sender"), 90)
                        assertEquals(Type.SESSION_END, read(peer).type)
                        assertEquals(-1, peer.inputStream.read())
                    }
                }
                await { events.count { it == ReceiverScreenEvent.CleanupFinished } >= 2 }
                var state = ReceiverScreen()
                events.forEach { state = state.reduce(it) }
                assertEquals(server.addresses().map { "$it:${server.listeningPort}" }, state.addresses)
                assertEquals(if (state.addresses.isEmpty()) ScreenPhase.NOT_READY else ScreenPhase.READY, state.phase)
                assertTrue(state.showWaiting)
                if (!orderly) assertEquals("PROTOCOL", state.problem)
                connect(server).use { peer ->
                    hello(peer)
                    send(peer, Type.SESSION_REQUEST, settings(), 91)
                    assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                }
            }
        }
    }

    @Test fun positiveInternalSessionAndReadinessChecksStillApplyToMedia() {
        ReceiverServer.forProtocolTests("Receiver").use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 23)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                Wire.write(peer.outputStream, Message(Type.VIDEO_FRAME, 23, 1, 1, 1_000_000,
                    flags = 1, payload = byteArrayOf(0, 0, 0, 1, 0x65)))
                assertEquals("PROTOCOL", read(peer).json().string("code"))
            }
        }
    }

    @Test fun peerAudioFailureRetainsOriginAndReasonThroughRealReaderAndCleanup() {
        val events = ConcurrentLinkedQueue<ReceiverScreenEvent>()
        val diagnostics = ConcurrentLinkedQueue<org.screencopy.receiver.ui.FailureDiagnostic>()
        ReceiverServer.forProtocolTests("Receiver", onScreenEvent = events::add, onFailure = diagnostics::add).use { server ->
            server.start()
            connect(server).use { peer ->
                hello(peer)
                send(peer, Type.SESSION_REQUEST, settings(), 77)
                assertEquals(Type.SESSION_ACCEPT, read(peer).type)
                send(peer, Type.ERROR, JSONObject().put("code", "AUDIO_FAILED")
                    .put("message", "peer-local detail must not be logged").put("fatal", true), 77)
                assertEquals(Type.ERROR, read(peer).type)
                assertEquals(-1, peer.inputStream.read())
            }
            await { events.count { it == ReceiverScreenEvent.CleanupFinished } >= 2 }
            assertEquals(1, diagnostics.size)
            val first = diagnostics.single()
            assertEquals(org.screencopy.receiver.ui.FailureOrigin.PEER, first.origin)
            assertEquals(org.screencopy.receiver.ui.FailureReason.PEER_REPORTED, first.reason)
            assertFalse(first.logMessage().contains("peer-local"))
            var state = ReceiverScreen()
            events.forEach { state = state.reduce(it) }
            assertEquals("AUDIO_FAILED", state.problem)
            assertEquals(org.screencopy.receiver.ui.FailureReason.PEER_REPORTED, state.failureReason)
            assertTrue(state.showWaiting)
        }
    }

    private fun connect(server: ReceiverServer): Socket = Socket(InetAddress.getLoopbackAddress(), server.listeningPort).apply { soTimeout = 3000 }
    private fun hello(peer: Socket, payload: JSONObject = helloPayload()): Message {
        send(peer, Type.HELLO, payload)
        val response = read(peer)
        assertEquals(Type.HELLO, response.type)
        assertEquals(0L, response.session)
        assertEquals(setOf("role", "versions", "name"), response.json().keys().asSequence().toSet())
        assertEquals("receiver", response.json().string("role"))
        assertEquals(3, response.json().array("versions").getInt(0))
        return response
    }
    private fun helloPayload() = JSONObject().put("role", "sender").put("versions", JSONArray().put(3)).put("name", "Phone")
    private fun send(peer: Socket, type: Type, json: JSONObject, session: Long = 0) = Wire.write(peer.outputStream, Message.json(type, json, session))
    private fun read(peer: Socket) = Wire.read(peer.inputStream)
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun settings() = StrictJson.decode(
        """{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"audio":{"enabled":false,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}""".toByteArray()
    )
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < end) Thread.sleep(1)
        assertTrue("Receiver did not complete cleanup", condition())
    }
}
