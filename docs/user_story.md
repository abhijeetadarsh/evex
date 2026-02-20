### **User Story: Implement Root-Level Touch Macro Engine**

**As a** mobile gamer and power user,

**I want to** record and replay raw, hardware-level touch inputs using a floating overlay,

**So that** I can perfectly automate complex, timing-sensitive in-game actions (like intricate build sequences or precise recoil control) with zero latency and 1:1 accuracy.

---

### **Acceptance Criteria (BDD Format)**

**Scenario 1: Root Initialization and Daemon Boot**

* **Given** the user launches the app for the first time,
* **When** the app initializes,
* **Then** it must request Superuser (root) permissions via the system's root manager (e.g., Magisk/KernelSU).
* **And** upon granting, the Kotlin frontend must successfully launch the C++ daemon process in the background.
* **And** an active UNIX Domain Socket connection must be established between the UI and the root daemon.

**Scenario 2: Activating the Floating Overlay**

* **Given** the app has `SYSTEM_ALERT_WINDOW` permissions,
* **When** the user toggles the "Enable Macro Overlay" switch in the main app,
* **Then** a persistent, draggable floating widget containing "Record", "Stop", and "Play" buttons appears on the screen, remaining visible over full-screen applications.

**Scenario 3: Flawless Input Recording**

* **Given** the floating overlay is active during a game,
* **When** the user taps "Record",
* **Then** the UI sends a `START_REC` signal over the socket.
* **And** the C++ daemon immediately begins reading raw `input_event` structs from the correct `/dev/input/eventX` touch node.
* **And** it saves the exact coordinates, multi-touch data, and microsecond-level timestamps to a local binary file without dropping frames or causing UI stutter.
* **When** the user taps "Stop", the daemon closes the file and awaits the next command.

**Scenario 4: Zero-Latency Playback**

* **Given** a macro has been successfully recorded and saved,
* **When** the user taps "Play" on the floating widget,
* **Then** the C++ daemon opens the saved binary file.
* **And** it writes the `input_event` structs back into the `/dev/input/eventX` node in `O_WRONLY` mode.
* **And** the playback executes with the exact original timing, perfectly simulating the physical touch sequence.

---

### **Technical Implementation Notes & Constraints**

* **Frontend Stack:** Kotlin, Android SDK.
* **Backend Stack:** C++ native executable (compiled via NDK for ARM64).
* **IPC:** Local UNIX Domain Sockets. The UI sends string/byte commands; the daemon executes low-level file I/O operations based on those commands.
* **Device Discovery:** The C++ daemon must dynamically identify the correct touch input node on startup (e.g., by parsing `getevent -p` or reading `/proc/bus/input/devices`), as the event number (`event2`, `event4`, etc.) varies between devices.
* **Data Structure:** The macro payload must be serialized efficiently. Using raw C-structs (`struct input_event`) mapped directly to binary files is the fastest method, avoiding JSON or XML parsing overhead.

