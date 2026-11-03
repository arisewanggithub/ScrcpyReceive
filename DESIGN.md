# ScrcpyReceive architecture

ScrcpyReceive accepts one Android screen/audio cast from ScrcpySend on a trusted
LAN. The application ID `org.screencopy.receiver` and `SCPY` framing are stable
compatibility identifiers. Wire protocol version 3 is separate from app release
versions. Wire schemas/resource bounds are in
[PROTOCOL.md](PROTOCOL.md); setup/build/release instructions are in
[README.md](README.md).

## Trust, discovery and admission

Discovery/control/media are unauthenticated and unencrypted, including SRT
with AES disabled. Names are labels, not identities. UDP advertisements carry
version, name, TCP port and available/busy state. The actual datagram source IP
is authoritative; matching names never redirect a sender. One accepted cast
owns a positive internal session ID and track generations. Other requests
receive BUSY or explicit configuration errors.

The receiver needs no ADB, root, capture permissions or Developer options.
Recording, remote input injection, internet traversal and background receiving
are outside its scope. Do not assume protection against observers or spoofed
clients on an untrusted network.

## SRT / fragmented MP4 pipeline

```text
wire protocol version 3 control / explicit SRT SESSION_ACCEPT / actual peer and session
  -> libsrt1.5.7 cancellable caller
  -> unseekable, uncached Media3 DataSource
  -> FragmentedMp4Extractor / standard fMP4 metadata
  -> bounded AVC/HEVC format bridge
  -> ExoPlayer1.11.1 renderers / Surface / AudioTrack
  -> actual video and requested-audio readiness
```

libsrt is transport only. Media3 owns demux, decoder selection, AudioTrack,
playback clocks and late-frame policy. Seeking, automatic reconnects and
alternate-backend retries are disabled. A present unsupported transport cannot
select custom TCP implicitly.

H.264/H.265/AV1 and Opus/AAC-LC/audio-off are negotiated; PCM is rejected for
SRT. Canonical OpusHead/dOps metadata preserves pre-skip/gain. AVC uses real
in-band SPS/PPS at IDR; HEVC uses complete real VPS/SPS/PPS at authentic IRAP.
The bounded bridge announces actual CSD/profile/dimensions before unchanged
sample bytes, PTS and key flags. Default decoder adaptation/reinitialization
retains session/audio ownership. No fake keyframes, repeated init segments or
custom playback clock are used.

AV1/audio initialization changes require a new supported session. Hardware
decoder adaptation/rotation remains a device-validation requirement, not a
guarantee from parsing valid metadata.

Native close precedes main-looper player release, waking a blocked loader.
Owned handles are published before connect. FIFO main posts and a closed gate
prevent late startup/callbacks from reviving a retired Surface; no lifecycle
monitor waits synchronously for main-thread cleanup.

## Custom TCP compatibility pipeline

```text
wire protocol version 3 listener / bounded framing / explicit stream configuration and readiness
  -> video generation queue -> MediaCodec -> Surface
  -> audio generation queue -> Opus/AAC/PCM decode -> AudioTrack
  -> bounded audio presentation clock / video presentation
```

The sender owns capture, encoders, geometry and typed options. The receiver
validates actual codec metadata/support and reports readiness before media.
It rejects unsupported formats rather than silently changing quality, disabling
requested audio or cropping the received image again.

libopus1.5.2 handles canonical Opus initialization, pre-skip, gain and decode.
AAC uses supported platform decoding; PCM retains its negotiated format.
Packet boundaries and FIFO audio payload order are preserved. Bounded framing,
queues and generation checks prevent stale media from crossing session
ownership. Missing/blocked output becomes an explicit typed failure rather
than fabricated progress.

## Presentation and session-owned orientation

The initialized Surface remains underneath an accessible waiting/status page.
Only real rendered-video and required-audio readiness admit Playing; a
connection/configuration callback alone cannot hide the waiting page.
Playing aspect-fits the actual video in available space with letterboxing,
not stretch or recrop. Portrait/landscape layouts, insets and overscan-safe
margins support phone/tablet and D-pad navigation without a TV certification
claim.

The optional session `receiverOrientation` is exactly auto/landscape/portrait.
It changes Activity presentation only, never capture geometry, packet metadata,
timestamps or generations. Auto retains OS policy; forced axes are requested
once after readiness, with the exact prior policy restored on owned teardown.
Large-screen, multi-window and fixed-orientation policies may ignore it.

Handled orientation/size changes refresh the waiting subtree/insets while
retaining valid Surface/session resources. Actual Surface loss, background
exit or unrelated Activity recreation still uses normal owned teardown.
No invalid Surface is ignored to preserve a cast.

## Resource lifetime, diagnostics and privacy

Stop/disconnect/failure closes matching connection, decoder/audio/native handles
and orientation lease before returning to Ready. Exit and Android Back/Home
leave through ordinary Activity cleanup, not process kill. Late callbacks and
previous-generation cleanup cannot retire a newer cast.

Screen-on is scoped to the visible Activity. Media is not recorded; backup/
device transfer is excluded and screenshots are blocked during presentation.
Concise user errors are separated from bounded, redacted technical diagnostics.
There are no automated power, keyguard or protected-device setting changes.

## Updates, verification and distribution

[UPDATES.md](UPDATES.md) defines stable HTTPS update lookup, exact manifest/hash/
single-signer/package checks, scoped FileProvider access and explicit
permission/install/retry flow. Checks/downloads do not stop playback.
Installation requires an idle cast and a fresh verified APK; cancellation or
an unconfirmed installer result is not reported as success.

CI checks both transport profiles and runs only the remote-update UI class
on a disposable API35 x86_64 emulator. Hardware decoding, audio routes,
endurance, latency and Stop/rotation need separate device validation.
Sender real H.265 capture/Stop/recast safety remains unverified because
kernel-panic risk has been reported; synthetic tests do not establish safety.

Keep pinned Opus/SRT archives, checksums, all bundled notices and required
corresponding sources. Original application code has no selected reuse grant;
third-party terms/copyright remain unchanged. Binary publication requires
manual distribution approval and signing; a successful build is neither a
legal compliance guarantee nor device/store certification.
