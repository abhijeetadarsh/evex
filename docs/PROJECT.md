# Macro Engine — Complete Project Documentation

> **Package:** `com.macro.engine`
> **Min SDK:** 26 (Android 8.0) · **Target SDK:** 34 (Android 14)
> **ABI:** `arm64-v8a` only
> **Requires:** Rooted device + Overlay permission

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Project Structure](#project-structure)
4. [The C++ Daemon (Backend)](#the-c-daemon-backend)
5. [The Kotlin Frontend](#the-kotlin-frontend)
6. [IPC Protocol](#ipc-protocol)
7. [Data Flow — Recording](#data-flow--recording)
8. [Data Flow — Playback](#data-flow--playback)
9. [Overlay System](#overlay-system)
10. [Macro File Format](#macro-file-format)
11. [Build & Packaging](#build--packaging)
12. [Permissions & Security](#permissions--security)
13. [UI / Design System](#ui--design-system)

---

## Overview

Macro Engine is an Android app that records and replays touch input at the kernel level. It captures raw `input_event` structs from `/dev/input/eventX` and replays them with accurate timing, creating pixel-perfect touch automation.

**Key capabilities:**
- Record touch input with a single tap to start
- Play back recorded macros at adjustable speed (0.25x — 4.0x)
- Loop playback (finite count or infinite)
- Floating overlay widget for control without leaving the current app
- Manage saved macros (list, play, delete)

---

## Architecture

```
┌──────────────────────────────────┐
│         Kotlin Frontend          │
│  (MainActivity, OverlayService)  │
│         untrusted_app SELinux    │
└───────────┬──────────────────────┘
            │  TCP 127.0.0.1:52398
            │  (newline-delimited ASCII)
┌───────────▼──────────────────────┐
│         C++ Daemon               │
│      (macro_daemon binary)       │
│         su (root) SELinux        │
│                                  │
│  ┌──────────┐  ┌──────────────┐  │
│  │ Recorder │  │    Player    │  │
│  │ (read)   │  │   (write)   │  │
│  └────┬─────┘  └──────┬──────┘  │
│       │               │         │
└───────┼───────────────┼─────────┘
        │               │
   ┌────▼───────────────▼────┐
   │  /dev/input/eventX      │
   │  (kernel input device)  │
   └─────────────────────────┘
```

**Why this architecture?**

- Reading/writing `/dev/input/eventX` requires root access
- The Android app runs as `untrusted_app` (no root)
- A C++ daemon launched via `su` operates in the root SELinux context
- TCP loopback (`127.0.0.1`) is used instead of UNIX domain sockets because SELinux blocks cross-context UNIX socket connections

---

## Project Structure

```
macro/
├── app/
│   ├── build.gradle.kts              # App-level Gradle config
│   └── src/main/
│       ├── AndroidManifest.xml        # Permissions, service declaration
│       ├── cpp/                       # C++ daemon source
│       │   ├── CMakeLists.txt         # Build config for daemon executable
│       │   ├── main.cpp               # Daemon entry point & command loop
│       │   ├── protocol.h             # IPC protocol constants
│       │   ├── device_discovery.cpp/h # Touch device auto-detection
│       │   ├── socket_server.cpp/h    # TCP loopback server
│       │   ├── recorder.cpp/h         # Input event recording
│       │   └── player.cpp/h           # Input event playback
│       ├── java/com/macro/engine/     # Kotlin source
│       │   ├── MainActivity.kt        # Main screen, daemon lifecycle
│       │   ├── OverlayService.kt      # Floating widget foreground service
│       │   ├── DaemonClient.kt        # TCP client for daemon IPC
│       │   ├── RootHelper.kt          # Root command execution utilities
│       │   └── MacroAdapter.kt        # RecyclerView adapter for macro list
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml  # Main screen layout
│           │   ├── overlay_widget.xml # Floating overlay layout
│           │   └── item_macro.xml     # Macro list item layout
│           ├── drawable/              # Vector icons (record, play, stop, etc.)
│           └── values/
│               ├── colors.xml         # Color palette (dark theme)
│               ├── strings.xml        # All user-facing strings
│               └── themes.xml         # Material theme definition
├── docs/                              # Documentation
├── config/detekt/                     # Static analysis config
├── build.gradle.kts                   # Root Gradle config
└── settings.gradle.kts                # Project settings
```

---

## The C++ Daemon (Backend)

The daemon is a standalone native executable (`macro_daemon`) that runs with root privileges. It is **not** a JNI library — it's a completely separate process.

### main.cpp — Entry Point

**Startup sequence:**
1. Register signal handlers for graceful shutdown (`SIGTERM`, `SIGINT`, ignore `SIGPIPE`)
2. Auto-discover the touch input device via `DeviceDiscovery::getBestTouchDevice()`
3. Start TCP server on `127.0.0.1:52398`
4. Enter main loop: accept client → process commands → handle disconnection → repeat

**Key details:**
- Only one client at a time (single-connection model)
- Worker operations (record/play) run on a separate `std::thread`
- Worker thread is joined before starting a new operation
- On client disconnect, any active recording/playback is stopped

### device_discovery.cpp — Touch Device Detection

Auto-detects the correct `/dev/input/eventX` node for the touchscreen.

**Detection algorithm (two-pass):**

1. **Primary:** Parse `/proc/bus/input/devices`
   - Read device blocks (separated by blank lines)
   - Extract device name from `N:` line
   - Extract event handler from `H:` line using regex `event(\d+)`
   - Verify via `ioctl(EVIOCGBIT(EV_ABS))` that the device supports `ABS_MT_POSITION_X` (multi-touch)

2. **Fallback:** Scan `/dev/input/` directory directly
   - Open each `eventX` node
   - Test for multi-touch capability via `ioctl`
   - Get device name via `ioctl(EVIOCGNAME)`

**Returns:** The path to the first multi-touch capable device (e.g., `/dev/input/event4`)

### recorder.cpp — Input Recording

Records raw `input_event` structs from the device node to a binary file.

**How it works:**
1. Open the input device (`O_RDONLY`) and output file (`O_WRONLY | O_CREAT | O_TRUNC`)
2. Send `REC_STARTED` callback to the UI
3. Enter recording loop:
   - Use `select()` with 100ms timeout to poll the device fd
   - On data: `read()` an `input_event`, `write()` it to the output file
   - On first event: Send `REC_FIRST_EVENT` callback (UI starts timer)
   - Every 100 events: `fsync()` to prevent data loss
   - Timeout/no data: Check `recording_` flag, loop again
4. On stop: Final `fsync()`, close fds, send `REC_STOPPED`

**Technical notes:**
- `select()` allows the loop to check the stop flag every 100ms without blocking indefinitely
- `recording_` is `std::atomic<bool>` for thread-safe stop signaling
- Events are written as raw binary (no encoding/serialization)

### player.cpp — Input Playback

Replays recorded events through the device node with accurate timing.

**How it works:**
1. Validate macro file exists and has content (≥ one `sizeof(input_event)`)
2. Open device node (`O_WRONLY`)
3. Send `PLAY_STARTED` callback
4. For each loop iteration:
   - Open macro file fresh
   - Read events one by one
   - Calculate delay between consecutive events: `timeDiffUs(prevEv, currentEv)`
   - Apply speed multiplier: `adjustedDelay = delayUs / speed`
   - Cap maximum delay at 10 seconds (prevents stuck playback if recording had long pauses)
   - **Save original event timestamp BEFORE clearing** (critical timing fix)
   - Clear timestamp (`tv_sec = 0, tv_usec = 0`) before `write()` — kernel sets its own
   - Between loops: send `PLAY_LOOP_ITER` callback, 100ms pause
5. Send `PLAY_DONE` (natural end) or `PLAY_STOPPED` (user stopped)

**Speed control:**
- `speed_` is `std::atomic<float>`, can be changed mid-playback
- Valid range: 0.0 (exclusive) to 10.0
- Speed > 1.0 = faster, speed < 1.0 = slower

### socket_server.cpp — TCP Server

Simple single-client TCP server on `127.0.0.1`.

| Method | Description |
|--------|-------------|
| `start(port)` | Create socket, `SO_REUSEADDR`, bind to loopback, listen(1) |
| `acceptClient()` | Blocking `accept()` call |
| `sendMessage(msg)` | Sends `msg + "\n"` to client |
| `receiveCommand()` | Reads one byte at a time until `\n`, strips `\r` |
| `disconnectClient()` | Close client fd |
| `shutdown()` | Close both client and server fds |

---

## The Kotlin Frontend

### MainActivity.kt — Main Screen

**Responsibilities:**
- Root permission check on first launch
- Deploy daemon binary from APK's native libs to a writable location
- Kill any existing daemon, launch fresh daemon via `su`
- Connect to daemon via TCP (retry up to 10 times)
- Display connection status (green/red dot)
- Manage overlay toggle switch
- Display saved macros list with play/delete actions

**Startup sequence (`initializeApp`):**
1. `RootHelper.checkRoot()` — verify root access
2. Deploy daemon binary: Copy from `nativeLibraryDir/libmacro_daemon.so` to `filesDir/macro_daemon`
3. `RootHelper.killDaemon()` — kill any previous instance
4. `RootHelper.launchDaemon()` — launch as root in background
5. Connect `DaemonClient` with retries
6. Check overlay permission, prompt if missing
7. Refresh macro list from storage

**Macro management:**
- Macros are stored in the app's external files directory under `macros/`
- `MacroAdapter` displays them in a RecyclerView sorted by date (newest first)
- Each item shows: friendly name, file size (KB), date
- Play button: Starts overlay service + sends `ACTION_PLAY_FILE` intent
- Delete button: Deletes file, removes from list, sends `STOP_PLAY` if that file was playing

### OverlayService.kt — Floating Widget Service

This is the largest and most complex file (~730 lines). It manages:
- The floating overlay widget (draggable, always-on-top)
- All daemon communication during recording/playback
- Recording UX flow (tap-to-start, timer, done/cancel/save)
- Playback controls (play, stop, loop, speed)
- Touch passthrough during playback
- Notification with action buttons
- Volume key listener for stop

**Service lifecycle:**
- Runs as a foreground service with `FOREGROUND_SERVICE_SPECIAL_USE`
- Started via `startForegroundService()` from MainActivity
- `START_NOT_STICKY` — does not auto-restart after being killed
- `stopWithTask = true` in manifest — killed when app swiped from recents
- `onTaskRemoved()` calls `stopSelf()` as additional cleanup

**Overlay widget features:**
- Draggable via touch — supports drag detection vs. click using a distance threshold (10dp)
- Touch passthrough during playback — widget becomes non-interactive so touches reach the app
- Window type: `TYPE_APPLICATION_OVERLAY`
- Flags: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN`

**State machine:**

| State | Description |
|-------|-------------|
| **Idle** | Connected to daemon, all buttons available |
| **Waiting for Touch** | After Record pressed, showing "Tap screen to start" |
| **Recording** | Timer running, Done button visible |
| **Recording Paused** | After Done pressed, Cancel/Save buttons visible |
| **Playing** | Touch passthrough enabled, Stop button active |
| **Pending** | Waiting for daemon acknowledgment (5s timeout) |
| **Countdown** | Pre-playback delay countdown |
| **Disconnected** | All buttons disabled |

### DaemonClient.kt — TCP Client

Kotlin coroutine-based TCP client for daemon communication.

| Method | Description |
|--------|-------------|
| `connect()` | Connect with up to 10 retries, 500ms between attempts |
| `sendCommand(cmd)` | Write command string via `PrintWriter` |
| `receiveResponse()` | Read single line via `BufferedReader` |
| `sendAndReceive()` | Send + await response |
| `startListening(scope)` | Continuous listener loop, dispatches to `onStatusReceived` on Main thread |
| `disconnect()` | Close writer, reader, socket |

**Key detail:** `startListening` runs on `Dispatchers.IO` but callbacks are dispatched to `Dispatchers.Main` via `withContext`.

### RootHelper.kt — Root Utilities

Singleton object for executing `su` commands.

| Method | Description |
|--------|-------------|
| `checkRoot()` | Runs `id` as root, checks for `uid=0` |
| `execRoot(cmd)` | Runs arbitrary command as root, returns stdout |
| `launchDaemon(path)` | `chmod 755` + launch in background with `&` |
| `killDaemon()` | `pkill -f macro_daemon` |
| `isDaemonRunning()` | `pgrep -f macro_daemon` |

### MacroAdapter.kt — RecyclerView Adapter

Displays saved macro files in the main activity.

- Shows friendly name (filename with underscores replaced, capitalized)
- Shows file size in KB and modification date
- Play button triggers `onPlayClick` callback
- Delete button triggers `onDeleteClick` callback and removes item from list

---

## IPC Protocol

All commands are **newline-terminated ASCII strings** over TCP `127.0.0.1:52398`.

### UI → Daemon (Commands)

| Command | Arguments | Description |
|---------|-----------|-------------|
| `PING` | — | Health check |
| `LIST_DEVICES` | — | List all touch devices |
| `START_REC` | `<filepath>` | Start recording to file |
| `STOP_REC` | — | Stop recording |
| `PLAY` | `<filepath>` | Play macro file once |
| `PLAY_LOOP` | `<count> <filepath>` | Play macro in loop (0 = infinite) |
| `STOP_PLAY` | — | Stop playback |
| `SET_SPEED` | `<multiplier>` | Set playback speed (e.g., "2.0") |
| `QUIT` | — | Shut down daemon |

### Daemon → UI (Responses)

| Response | Arguments | Description |
|----------|-----------|-------------|
| `PONG` | — | Response to PING |
| `OK` | — | Generic success |
| `ERROR` | `<message>` | Error with description |
| `DEVICE` | `<path> <name>` | Device info (from LIST_DEVICES) |
| `REC_STARTED` | — | Recording has begun |
| `REC_FIRST_EVENT` | — | First input event captured |
| `REC_STOPPED` | — | Recording ended |
| `PLAY_STARTED` | — | Playback has begun |
| `PLAY_DONE` | — | Playback finished naturally |
| `PLAY_STOPPED` | — | Playback stopped by user |
| `PLAY_LOOP_ITER` | `<iteration>` | Loop iteration completed |
| `SPEED_SET` | `<speed>` | Speed change confirmed |

---

## Data Flow — Recording

```
┌─────────┐     ┌──────────────┐     ┌────────┐     ┌────────┐
│  User   │────▶│ OverlayService│────▶│ Daemon │────▶│  .bin  │
│ taps    │     │ btn Record   │ TCP │ START  │     │  file  │
│ Record  │     │              │     │  _REC  │     │        │
└─────────┘     └──────────────┘     └────┬───┘     └────────┘
                                          │
                "Tap screen to start"      │ Daemon opens:
                appears on overlay         │  /dev/input/eventX (read)
                                          │  output.bin (write)
                                          ▼
                                    REC_STARTED sent
                                          │
                ┌─────────────────────────┤
                │ User touches screen     │
                │ (goes through to app)   │ Daemon reads raw input_event
                │                         │ writes to .bin file
                │                         │
                │ First event ──────────▶ REC_FIRST_EVENT sent
                │                         │ (UI starts timer)
                │                         │
                │ User taps "Done" ──────▶ STOP_REC sent
                │                         │
                │                    REC_STOPPED sent
                │                         │
                ▼                         ▼
          Cancel/Save UI appears    File is on disk
```

**The recording starts immediately** when Record is pressed (daemon opens the device fd right away). The "Tap screen to start" message is purely visual — the daemon is already capturing events. This ensures the **first touch IS recorded**.

The `REC_FIRST_EVENT` notification triggers the recording timer UI. Before this, the "Tap screen to start" message is visible.

---

## Data Flow — Playback

```
┌─────────┐     ┌──────────────┐     ┌─────────┐     ┌────────────┐
│  User   │────▶│ OverlayService│────▶│  Daemon │────▶│ /dev/input │
│ taps    │     │ btn Play     │ TCP │  PLAY   │     │  /eventX   │
│ Play    │     │              │     │ <path>  │     │  (write)   │
└─────────┘     └──────────────┘     └────┬────┘     └────────────┘
                                          │
              3-2-1 countdown shown       │
              on overlay widget           │
                                          ▼
                                    PLAY_STARTED sent
                                          │
              Overlay becomes             │ Read events from .bin
              touch-passthrough           │ Calculate delay between events
                                          │ Apply speed multiplier
              Stop button only            │ Write event to device fd
              (or Volume Down)            │ Kernel injects the touch
                                          │
                                          ▼
                                    PLAY_DONE / PLAY_STOPPED
```

**Playback timing:**
- Original timestamps are preserved in the recording
- Delta time = `event[n].time - event[n-1].time` (microsecond precision)
- Adjusted delay = `delta / speed_multiplier`
- Events with delays > 10 seconds are capped
- The event timestamp is zeroed before `write()` — the kernel assigns its own

**Touch passthrough:** During playback, the overlay widget window flags are changed to include `FLAG_NOT_TOUCHABLE`, allowing all user touches to pass through to the underlying app.

---

## Overlay System

### Widget Layout (`overlay_widget.xml`)

A `MaterialCardView` with rounded corners (28dp), semi-transparent dark background, and blue border.

**Layout hierarchy:**

```
MaterialCardView (overlay_bg + overlay_border)
└── LinearLayout (vertical)
    ├── tvCountdown (hidden) — "3", "2", "1" during pre-play
    ├── LinearLayout (horizontal) — Main controls
    │   ├── btnRecord (red circle)
    │   ├── btnStop (orange circle)
    │   ├── btnPlay (green circle)
    │   └── btnLoop (toggle, grey/active)
    ├── layoutSpeed (hidden) — Speed controls
    │   ├── tvSpeed ("1.0×")
    │   ├── btnSpeedDown ("−")
    │   └── btnSpeedUp ("+")
    ├── tvTapToStart (hidden) — "Tap screen to start"
    ├── layoutRecording (hidden) — Timer row
    │   ├── tvRecStatus ("● Rec 00:05")
    │   └── tvDone ("Done")
    └── layoutRecActions (hidden) — Post-recording
        ├── btnCancel (orange)
        └── btnSave (green)
```

### Window Management

The overlay uses `WindowManager.LayoutParams` with:
- `TYPE_APPLICATION_OVERLAY` — floats above all apps
- `FLAG_NOT_FOCUSABLE` — doesn't steal keyboard focus
- `FLAG_LAYOUT_IN_SCREEN` — uses absolute screen coordinates
- `WRAP_CONTENT` dimensions
- User-draggable via touch events

### Dragging

- `ACTION_DOWN`: Record start position
- `ACTION_MOVE`: Update window params with delta, move overlay
- `ACTION_UP`: If movement < 10dp threshold, treat as click instead of drag

### Notification

Persistent foreground service notification with:
- Channel: "Macro Engine Service"
- Content: "Macro overlay is active" (updates based on state)
- Actions: Record / Play / Stop buttons
- Priority: Low (no sound/vibration)

---

## Macro File Format

Macros are stored as **raw binary files** containing sequential `input_event` structs.

```c
struct input_event {
    struct timeval time;  // 16 bytes (tv_sec + tv_usec)
    __u16 type;           // 2 bytes (e.g., EV_ABS = 3)
    __u16 code;           // 2 bytes (e.g., ABS_MT_POSITION_X = 0x35)
    __s32 value;          // 4 bytes (coordinate value)
};
// Total: 24 bytes per event
```

**File structure:** `[event_0][event_1][event_2]...[event_n]`

- No header, no metadata, no compression
- File size = `num_events × 24 bytes`
- Timestamps are the original kernel timestamps (used for timing calculation)
- Typical touch gesture generates hundreds of events per second

**Storage location:** `/sdcard/Android/data/com.macro.engine/files/macros/`

**Naming convention:** `macro_YYYYMMDD_HHMMSS.bin`

---

## Build & Packaging

### Gradle Configuration

- **Kotlin:** 1.8 JVM target
- **View Binding:** Enabled
- **Linting:** ktlint (Android mode) + detekt (static analysis)
- **NDK:** CMake 3.22.1, C++17, arm64-v8a only

### Daemon Packaging Trick

Android's AGP only packages shared libraries (`.so`) from CMake, not executables. The daemon is an executable, so a workaround is used:

1. CMake builds `macro_daemon` as an executable
2. A `POST_BUILD` custom command copies it to `src/main/jniLibs/<abi>/libmacro_daemon.so`
3. AGP packages it as if it were a native library
4. At runtime, `MainActivity` copies it from `nativeLibraryDir` to `filesDir` and makes it executable

### Dependencies

| Library | Purpose |
|---------|---------|
| `core-ktx` | Kotlin Android extensions |
| `appcompat` | Backward-compatible widgets |
| `material` | Material Design components |
| `constraintlayout` | Main activity layout |
| `lifecycle-runtime-ktx` | lifecycle-aware coroutines |
| `lifecycle-service` | LifecycleService base class |
| `kotlinx-coroutines-android` | Kotlin coroutines |
| `recyclerview` | Macro list display |

---

## Permissions & Security

### Required Permissions

| Permission | Purpose |
|------------|---------|
| `SYSTEM_ALERT_WINDOW` | Floating overlay widget |
| `FOREGROUND_SERVICE` | Keep overlay service alive |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ foreground service type |
| `INTERNET` | TCP loopback socket to daemon |
| `POST_NOTIFICATIONS` | API 33+ notification requirement |
| **Root (su)** | Launch daemon, access `/dev/input/` |

### Security Model

- The daemon runs as **root** via `su` — it has full kernel-level access
- The app (untrusted_app) communicates only via TCP loopback
- TCP is bound to `127.0.0.1` only — not accessible from other devices
- Port `52398` is hardcoded on both sides
- No authentication on the socket (trusted loopback environment)

---

## UI / Design System

### Color Palette (Dark Theme)

| Token | Hex | Usage |
|-------|-----|-------|
| `background_dark` | `#0D1117` | App background |
| `surface_dark` | `#161B22` | Cards |
| `surface_elevated` | `#21262D` | Buttons, elevated surfaces |
| `card_border` | `#30363D` | Card stroke |
| `accent_primary` | `#58A6FF` | Primary accent (blue) |
| `accent_record` | `#FF6B6B` | Record button (red) |
| `accent_play` | `#3FB950` | Play button (green) |
| `accent_stop` | `#FFA657` | Stop button (orange) |
| `text_primary` | `#F0F6FC` | Main text |
| `text_secondary` | `#8B949E` | Subdued text |
| `text_muted` | `#484F58` | Disabled/hint text |
| `overlay_bg` | `#E6161B22` | Overlay widget background (90% opaque) |
| `overlay_border` | `#58A6FF` | Overlay widget stroke |

### Theme

- Based on `Theme.MaterialComponents.DayNight.NoActionBar`
- Status bar: `background_dark`
- Navigation bar: `background_dark`
- All controls use the dark palette

### Drawable Icons

All icons are vector drawables (`24dp × 24dp`):
- `ic_record.xml` — Filled circle
- `ic_play.xml` — Right-pointing triangle
- `ic_stop.xml` — Filled square
- `ic_loop.xml` — Circular arrows
- `ic_delete.xml` — Trash can
- `status_dot_green.xml` — Small green circle (connected)
- `status_dot_red.xml` — Small red circle (disconnected)
