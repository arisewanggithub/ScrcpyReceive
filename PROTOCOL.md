# ScrcpySend / ScrcpyReceive wire protocol

Framing version field: **3**. This is a protocol compatibility discriminator,
separate from application release versions.
ScrcpySend / ScrcpyReceive use plain TCP control: no TLS, authentication, QR, permanent peer identity,
credential issuance, saved trust or revocation. Anyone on the LAN can observe or
forge discovery/session/media traffic. Use ONLY a trusted private family LAN.
No human session code, pairing code, peer credential or comparison confirmation
exists. Internal cast session IDs, message error `code` fields, track IDs and
generations identify internal resources. Sender capture uses Shizuku; receiver has no
dependency on sender's privileged backend.

## Listener selection and discovery

Receiver prominently displays actual listening IPv4 addresses and their bound TCP
port, with name as a label. TCP27184 is the default in discovery/internal
configuration and sender manual-address compatibility. Sender selects an
endpoint by actual UDP source IP + TCP port, with name as a label only, never
identity. One cast at a time; busy never evicts a cast.

Every 2s broadcast UTF-8 JSON to UDP 27183 (<=1024 bytes):

```json
{"protocol":"screencopy","version":3,"name":"Living room","port":27184,"state":"available"}
```

Name 1..80 characters; port integer1..65535;
state available/busy. Ignore unknown fields, never use an advertised host. Sender
lists packet SOURCE IP + name + port/state. Key by source IP+TCP port, update
name/state on fresh datagrams, expire after6s, at most64 entries. Select directly
or manually enter IP/address+port. No compare checkbox/modal or public code.
A listener restart at the same endpoint does not invalidate selection by itself.
Discovered expiry/unavailable state is reported; never silently redirect to a
different IP/port merely because its name matches. Actual connection and BUSY
responses remain authoritative; manual endpoints may lack discovery entirely.
Do not persist peer selection or inferred acceptance. Capture settings persist.
Unknown JSON fields (including human `code`/`sessionCode` fields in a
HELLO/discovery object) are IGNORED, never interpreted/validated as identity.
Wire/discovery versions must be 3; other values are rejected/ignored.

## Connection and session setup

Default TCP **27184**, configurable. TCP_NODELAY. Plain TCP only, no dual-mode
TLS autodetection. One bidirectional connection/one session. Sender connects and
sends HELLO first, session/track/generation0, PTS-1:

```json
{"role":"sender","versions":[3],"name":"ScrcpySend"}
```

Receiver validates role/version/name and returns HELLO session0:

```json
{"role":"receiver","versions":[3],"name":"Living room"}
```

Sender requires wire protocol version 3/role/name (1..80 chars). This is protocol setup, not
authentication. Then sender SESSION_REQUEST uses a new random positive
signed64 session ID and requested settings JSON below. No AUTH message exists:
type2 is RESERVED and MUST reject before payload allocation. Receiver checks
busy/config and returns SESSION_ACCEPT `{"tracks":[1,2]}` (or `[1]` audio off)
or REJECT `{"code":"BUSY","message":"Receiver already playing"}` /
UNSUPPORTED_CONFIG, with matching session. Receiver can reply REJECT BUSY at
HELLO/session0 too. Capture starts ONLY
after SESSION_ACCEPT. Enabled audio cannot silently disappear.

Incorrect version fields fail VERSION immediately. TLS ClientHello fails magic immediately.
Do not wait for a complete40-byte header to reject invalid magic/version. Setup and
individual startup phases10s overall (trickled reads do not extend); writer10s.

## SRT fragmented MP4 media transport

SRT is explicitly selected alongside the default custom TCP compatibility path.
Install matching SRT-enabled sender and receiver APKs.
An incompatible peer MUST NOT cause silent fallback or capture.
The request contains exactly:

```json
{"mediaTransport":{"kind":"srt","container":"fmp4","url":"srt://192.168.1.25:43517","latencyMs":100,"streamId":"sc-0000000000000001"}}
```

The descriptor has exactly these five fields. `kind` is `srt`, `container` is
`fmp4`, and `latencyMs` is an integer20..2000 (sender uses100).
`streamId` is `sc-` followed by the positive control-header session ID as exactly
16 lowercase hexadecimal digits, zero-padded. The example uses session1.
The URL is plain SRT with an explicit ephemeral port1..65535, numeric IP only,
no DNS, userinfo, path (including `/`), query or fragment. IPv6 uses brackets.
Its host MUST match the actual sender TCP control-socket peer IP; the sender
advertises its actual control-socket local IP, never a saved/discovered host.
The sender creates its interface-bound SRT listener after control connection and
before SESSION_REQUEST; it accepts only the control peer IP and matching stream
ID. This is session routing on a trusted LAN, NOT authentication.

The receiver validates the descriptor, session and requested codecs before
`SESSION_ACCEPT {"tracks":[1,2],"mediaTransport":{"kind":"srt"}}`
(`[1]` audio off). The sender requires that explicit echo before capture.
Malformed, unsupported, unavailable or mismatched SRT requests are rejected with
UNSUPPORTED_CONFIG, never reinterpreted as custom TCP. A present descriptor
with any unsupported kind is likewise rejected.
The absent `mediaTransport` field alone selects custom TCP.
The receiver then connects as SRT caller. Capture still starts ONLY after
the user's Play and SESSION_ACCEPT; no automatic retry or new capture.

SRT transports an ordered byte stream of standard fragmented MP4: one init
segment, followed by `moof`/`mdat` fragments with per-track `tfdt` decode times.
No custom STREAM_CONFIG, STREAM_READY, VIDEO_FRAME or AUDIO_PACKET messages
are allowed on this session's control connection. The sender flushes fragments
at100ms independently of future video keyframes, including a static first frame
with continuing audio. AVC uses the standard `avc3` sample entry: H.264
configuration/dimension changes (including sender rotation) retain the session
and send the actual new SPS/PPS in-band before the next real IDR access unit.
Identical encoder-restart configurations use the same rule. The initial `avcC`
is written once; there are no repeated init segments, invented keyframe flags,
discarded frames or orientation locks. A configuration not followed by a real
IDR fails explicitly. Changed audio configuration or a different video codec
still requires a new session. Supported SRT selections are H.264, H.265 or AV1
with Opus or AAC-LC or audio off; no codec/FPS/bitrate substitution.
Real H.265 capture/Stop safety is unverified; do not automate it as acceptance.

HEVC uses standard `hev1`/`hvcC`, serialized by the official Media3 muxer from
actual complete VPS/SPS/PPS. Parameter arrays permit later in-band updates.
Configuration/dimension changes (including rotation) prepend the complete actual
VPS/SPS/PPS before the next authentic IRAP picture, retaining one init segment.
The official extractor converts length-prefixed HEVC NALs to Annex B; the bounded
bridge uses official HEVC SPS/profile metadata to announce actual CSD/dimensions
before the unchanged access unit. Missing/inconsistent parameters or non-IRAP
activation fail explicitly. Raw PTS, key flags, FIFO, audio and the SRT connection
remain intact; no repeated moov, invented CSD/keys, clock reset or capture Stop.
Both applications must support this configuration handling. Hardware rotation/
decoder adaptation requires validation. Real H.265 display-capture/
Stop/recast safety remains unverified because kernel-panic risk has been reported.

AAC uses standard `mp4a`/`esds` from actual stereo 48 kHz AAC-LC
AudioSpecificConfig, and raw access units (never ADTS). ASC frameLengthFlag
selects 1024 or 960 samples per access unit. Unsupported profiles/extensions,
core/channel formats and configuration changes fail explicitly.
AV1 uses standard `av01`/`av1C`, with the official Media3 muxer deriving the
record from actual sequence/config OBUs in the first access unit; if absent,
the sender prepends its actual encoder configuration OBUs, never fake CSD.
Actual sequence dimensions must match the scrcpy metadata. AV1 CSD/sequence/
dimension changes are explicitly unsupported; this is not AVC rotation.
Encoder inventory and exact geometry/FPS/bitrate preflight must succeed;
receiver platform decoder availability is checked before SESSION_ACCEPT.
Unavailable codec returns UNSUPPORTED_CONFIG before capture, never a substitution.
HEVC/AAC/AV1 standard-container support does not establish device-specific hardware
playback. Validate decoder and audio readiness on the target hardware.

One shared timestamp origin preserves initial A/V source offsets. Video keeps
source presentation timestamps. Opus FIFO/payloads, pre-skip and gain are
retained; mux presentation timestamps are anchored once to the first audio
source PTS and advanced by each actual Opus packet duration, so legitimate
source audio PTS reversals do not reorder, drop or clamp packets. The muxer
does not implement a receiver playback clock, decoder or resampler.
AAC uses a rational sample-count timeline anchored once to the first source
observation with the same FIFO/payload/common-origin guarantees, without
accumulating rounded per-packet microsecond durations.

Receiver Media3 owns fMP4 demux, platform codecs, A/V timing and playback.
Its official fragmented MP4 extractor recognizes `avc3` and preserves in-band
NAL units. A bounded AVC TrackOutput bridge uses Media3's SPS parser to announce
the actual new CSD, profile and dimensions before committing that same unmodified
IDR sample. Default Media3 renderers handle codec adaptation/reinitialization;
AV1 OBUs bypass all AVC NAL parsing, retaining payload/encoder key flags.
Extracted track MIME must match the accepted selection; AAC ASC must match
its sample entry rate/channel metadata. Audio-off forbids an audio track.
No decoder backend, audio clock, timestamp or user capture setting is replaced.
Actual decoder video-size callbacks update receiver Surface aspect/diagnostics.
Both applications must support this configuration handling; a wire protocol
version match alone does not establish decoder rotation support.
No first-party AudioPlayer/VideoPlayer/PlaybackClock is used for this path.
Only after a real rendered first video frame AND position advancement of enabled
audio does the receiver send
`STATUS {"state":"srt-playing","message":"Media3 first video frame and enabled audio advancing"}`.
Audio-off requires the rendered frame only. Merely connecting SRT, parsing the
init segment, configuring codecs or entering a player-ready state is NOT proof
of playback. Existing10s startup deadline, PING/PONG, STOP/SESSION_END/ERROR/BUSY,
single-active ownership and cancellation remain in force. Close native input
before joining workers or releasing Media3 so blocked reads/writes are cancelled.
Errors are explicit and cannot silently reopen the source.

The SRT APK contains pinned libsrt1.5.7.
SRT encryption is compiled OFF for this trusted-LAN transport; stream ID
and peer pinning do not provide confidentiality or authentication. Live/message
transport uses payloads<=1316 bytes and disables late-packet dropping because
discarding container bytes corrupts the stream. A stalled sender fails explicitly
after one second without write progress. Native close must unblock accept,
connect, read and backpressured write without detached workers.
`protocol/fixtures/srt-fmp4.json` is the canonical paired SRT fixture.

## TCP binary framing (all control paths)

Exactly40-byte header, big endian:

| Offset | Field |
|---|---|
|0|4 ASCII bytes `SCPY`|
|4|u16 version **3**|
|6|u16 type|
|8|u32 payload length excluding header|
|12|u32 flags:1 video keyframe, otherwise0|
|16|u64 session, positive signed64 range;0 before SESSION_REQUEST|
|24|u32 track0 control,1 video,2 audio|
|28|u32 generation,1..2147483647 on track messages,0 control|
|32|i64 PTS microseconds; -1 controls/config|

Types:1 HELLO, **2 reserved/reject**,3 SESSION_REQUEST,4 SESSION_ACCEPT,5 REJECT,
6 STREAM_CONFIG,7 STREAM_READY,8 VIDEO_FRAME,9 AUDIO_PACKET,10 STATUS,11 ERROR,
12 PING,13 PONG,14 STOP,15 SESSION_END. Directions/responsibilities:
sender sends REQUEST/CONFIG/media, receiver sends ACCEPT/REJECT/READY; other
status/liveness/stop messages bidirectional. Only CONFIG/READY/media have
nonzero track/generation AND require session>0. Video track1; audio track2.
Media PTS>=0, config/control PTS-1. Keyframe flag only VIDEO_FRAME. Validate
header fields and bounds BEFORE allocation: controls/config<=65536, video<=4194304,
audio<=262144; no zero-length media. Unknown version/type/flags/invalid track
fatal. Exact reads across segmentation, preserve coalesced frames. EOF partway
through header/payload is error. Controls/config UTF-8 strict JSON objects;
reject duplicate keys, malformed UTF-8, surrogate errors, NaN, coercion or
non-integer/out-of-signed32 numbers unless explicitly otherwise. Ignore unknown
object fields but validate required field types/enums. Media binary, not Base64.

## Requested settings

```json
{"video":{"codec":"h264","encoder":"","bitrate":8000000,"maxSize":1920,"maxFps":60,"displayId":0,"orientation":"0","crop":null,"codecOptions":""},"receiverOrientation":"auto","audio":{"enabled":true,"codec":"opus","encoder":"","bitrate":192000,"source":"output","codecOptions":""}}
```

Video h264/h265/av1; audio opus/aac/raw. Encoder empty means explicit automatic.
The sender requires official hardware-accelerated/nonsoftware encoder flags only
for video. Opus/AAC accepts all compatible encoders, hardware or software.
Saved automatic resolves to an eligible explicit name before
negotiation/preflight/launch without changing persisted preferences. A saved
software video/unavailable selection, absent hardware video encoder or absent
compatible audio encoder fails explicitly; there is no automatic codec/audio-off
fallback or software video fallback. Raw PCM is unencoded,
not hardware-accelerated. This sender policy does not restrict receiver decoders.
Bitrates bits/s; maxSize0 unlimited or<=16384; maxFps1..240; displayId>=0.
Orientation0/90/180/270 or @0/@90/@180/@270; crop null or
`{"width":1280,"height":720,"x":0,"y":0}` natural-display coordinates, positive
size/nonnegative offsets. codecOptions validated scrcpy typed numeric list,
never arbitrary shell text. Receiver evaluates decodability, not encoder names.
No substitutions, B-frames forbidden. Raw ignores bitrate (field still positive).
Defaults H264 + enabled Opus192000; no sender Opus encoder is an explicit error.
Sender supports H264/H265/AV1 and Opus/AAC-LC/PCM; enabled PCM is Custom TCP only.

Optional top-level `receiverOrientation` is exactly one of the case-sensitive
strings `auto`, `landscape`, `portrait`; absent means `auto`. Present null,
non-string or any other value is invalid requested configuration and is rejected
with `UNSUPPORTED_CONFIG`. Senders emit the selected value, including `auto`.
This optional field uses wire protocol version 3.

The field controls only the receiver's presentation orientation for that accepted
session. `auto` leaves the receiver's normal automatic presentation policy;
`landscape`/`portrait` request the corresponding receiver presentation axis.
Apply the requested axis on entering Playing, not while idle or deciding whether
to accept a request. Orientation-only configuration changes must not recreate
the receiver Activity or disconnect the cast; handling orientation/screen-size
configuration changes in place is permitted. Genuine unrelated Activity teardown
or Surface loss retains existing ownership and synchronous decoder-retirement
rules; this extension does not require retaining a cast across arbitrary
Activity recreation. The receiver restores its prior presentation policy on
terminal/exit, and `auto` applies no override. It must not leak a forced
orientation into a later automatic session. This field is independent of
`video.orientation` (source capture), does not rotate encoded content, and does
not alter STREAM_CONFIG dimensions, media bytes, generation/track IDs, PTS or
readiness. Aspect-fit presentation still uses actual encoded dimensions.
Deterministic schema cases are in
[`receiver-orientation.json`](protocol/fixtures/receiver-orientation.json).

## Config, media, generations and readiness

STREAM_CONFIG JSON per track, generation1 initially:

```json
{"codec":"h264","width":1920,"height":1080,"csd":["AAAAAWdkACg=","AAAAAWju"]}
```

Actual dimensions1..16384. CSD standard canonical padded Base64 buffers:
H264 AnnexB SPS/PPS (one or separate buffers), H265 combined AnnexB VPS/SPS/PPS,
startcodes preserved. Each video frame one complete AnnexB AU, not AVCC/hvcC.
AV1 low-overhead OBU media, sequence-header OBU CSD, no IVF/container.
New config increments ONLY that track generation sequentially. Drain ordered
old media before new CONFIG; no old generation media thereafter. Rotation
updates actual SPS-derived dimensions; unchanged audio generation stays valid.

Opus config:

```json
{"codec":"opus","sampleRate":48000,"channels":2,"csd":["T3B1c0hlYWQBAjgBgLsAAAAAAA=="],"codecDelayNs":6500000,"seekPrerollNs":80000000}
```

csd[0] canonical19-byte RFC7845 OpusHead, version1, channels1/2, mapping0, NOT
Android AOPUSHDR/DLY/PRL wrappers or Ogg. LE preSkip u16offset10, inputRate
u32offset12, gain i16offset16. rate ALWAYS48000; channels must match header.
codecDelayNs=floor(preSkip*1e9/48000), preroll80000000. Receiver applies preSkip
once per generation and output gain. Each AUDIO_PACKET one raw Opus packet.
PTS names the source-clock observation for the decoded packet before preSkip;
receiver trims startup samples. Opus packet/decode order is arrival order, not
PTS order: the pinned scrcpy encoder may reconstruct output PTS independently
of its monotonic input timestamps and produce nonnegative reversals.
See pinned [AudioEncoder.fixTimestamp](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/audio/AudioEncoder.java#L134-L150)
and desktop [audio_regulator.c](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/audio_regulator.c#L140-L172).
AAC-LC48k stereo config csd[0] AudioSpecificConfig, raw AU packets, no ADTS.
PCM config `{"codec":"raw","sampleRate":48000,"channels":2,"csd":[],"sampleFormat":"s16le"}`;
packets interleaved signed16LE, lengths divisible by4.

Receiver sends STREAM_READY matching session/track/generation `{"ok":true}`
only after resources initialized. Sender buffers but sends no ordinary media
until ALL enabled tracks initially ready, timeout10s. New track generations
pause that track until READY; other tracks may continue. Reject stale generations.
Config packets never masquerade as media. Decoder failure explicit.

PTS scrcpy shared MediaCodec capture-clock microseconds, not Unix time, not
separately zeroed tracks; may start large. Video, AAC and PCM PTS are
nondecreasing per generation, equal allowed. Opus source PTS must be nonnegative
but may decrease anywhere within a generation, without a magnitude/startup
threshold. Identify Opus only by the exact string `"opus"` in the accepted
audio STREAM_CONFIG `codec`; other or missing codec values grant no exception.
Retain every packet, payload, PTS and arrival/decode order; do not sort, drop,
clamp, duplicate or create generations to hide source-clock reversals.
Receiver maps source observations against increasing decoded sample positions
to monotonic presentation, audio master when enabled; preserve initial A/V
offsets and apply preSkip once per generation. No B-frame reordering.

Both applications must preserve Opus source order and accept nonnegative PTS
reversals. A wire protocol version match alone does not establish this behavior.
`protocol/fixtures/opus-pts-order.json`
is the shared source-order acceptance fixture.

## Liveness, stop, errors, bounds

PING/PONG `{"nonce":123}`, echo signed32 integer. Either peer sends at least
every2s idle, any valid incoming traffic updates liveness,10s no traffic fatal.
One serialized writer and independent reader/watchdog. STOP `{"reason":"user"}`
stops both tracks/capture; SESSION_END echoes reason and closes within2s.
Disconnect/EOF/error closes resources, no old-session resume. New attempt uses a
new positive cast session ID and an explicitly selected/manual endpoint.

STATUS `{"state":"Streaming","message":"..."}` diagnostic.
ERROR `{"code":"AUDIO_FAILED","message":"...","fatal":true}` terminal if fatal.
REJECT always terminal. Codes VERSION, BUSY, UNSUPPORTED_CONFIG,
PROTOCOL, CAPTURE_FAILED, AUDIO_FAILED, DECODER_FAILED, TIMEOUT, CONGESTION, INTERNAL.
AUTH_FAILED is not a wire protocol version 3 error code. No remote phone input/control.

Sender aggregate media<=8MiB AND<=500ms PTS span/track, encoded receiver same
maximum (stricter allowed if explicit CONGESTION).
Track PTS span is maximum minus minimum across queued media plus the proposed
packet, not last minus first in arrival order; configurations do not contribute.
Controls/config64 entries/256KiB. Decoded video<=3frames/audio<=250ms. One in-progress max-size frame
additional, OS socket buffers measured separately. Fair round-robin media,
controls prioritized. Exceeded bounds stop explicitly, no arbitrary dependent
video dropping or unbounded latency. Sender nonsticky foreground service.
All streams, threads, guardian and capture resources cleaned on stop/error.

## Fixtures

`protocol/fixtures/v3.json`: deterministic exact framing cases, including invalid
versions 1/2, reserved type 2 (AUTH), malformed and valid frames. `discovery.json`, `session.json`
test source-address/code-free HELLO/internal-session shapes; `opus-head.hex` and
`track-lifecycle.json` provide initialization/readiness/generation examples.
Synthetic media/config fixtures are framing/schema tests, not playable content.
