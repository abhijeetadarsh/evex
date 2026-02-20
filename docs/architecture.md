Choosing to build at the root level gives absolute, uncompromising control. To interact directly with the Linux kernel's input subsystem (the `/dev/input/eventX` nodes), your app needs an architecture that separates the user interface from the highly privileged, low-level execution environment.

Because Android UI processes cannot hold root permissions directly, you will need to design a split architecture: a standard Android app for the interface, and a standalone native daemon running as root to handle the heavy lifting.

Here is the blueprint for how to structure this app.

### 1. The Core Engine (The Root Daemon)

This is the brain of your macro app. Since you need to process thousands of raw hardware input events per second with absolute zero latency, utilizing C++ through the Android NDK (Native Development Kit) is the most efficient approach.

* **What it is:** A standalone C++ executable compiled for Android.
* **How it runs:** Your main app will use a superuser command (`su`) to launch this C++ binary in the background. Because it is launched via `su`, the binary inherits root privileges.
* **Responsibilities:**
* **Device Discovery:** Scanning `/dev/input/` to identify the correct event nodes for the touchscreen (and physical buttons, if you want to record volume key macros).
* **Recording:** Reading raw `input_event` C-structs (defined in `<linux/input.h>`) directly from the node and writing them to a local file.
* **Playback:** Reading those saved structs and writing them back into the `/dev/input/eventX` node at the precise timestamps they were recorded.



### 2. The App UI & Floating Overlay (The Frontend)

This is the standard Android application layer that the user interacts with.

* **What it is:** A standard Kotlin or Java Android app.
* **Responsibilities:**
* **Macro Management:** A UI to name, organize, and edit saved macros.
* **Floating Window:** A persistent overlay service (using `SYSTEM_ALERT_WINDOW` permissions) that stays on top of your games. This provides the floating "Record", "Stop", and "Play" buttons.
* **Permission Handling:** Requesting root access via Magisk or KernelSU when the app first launches.



### 3. The Communication Bridge (Inter-Process Communication)

Because your UI (running as a normal user) and your C++ engine (running as root) are completely separate processes, they need a way to talk to each other in real-time.

* **The Mechanism:** Local UNIX Domain Sockets (`LocalSocket` in Android).
* **How it works:** * When your C++ root daemon starts, it opens a local socket and listens for instructions.
* When you tap "Record" on your floating overlay, the Kotlin app sends a simple string command (like `START_REC_MACRO_1`) over the socket.
* The C++ daemon receives the command and instantly begins reading the `/dev/input` nodes.
* When you tap "Stop", the Kotlin app sends `STOP_REC`, and the C++ daemon stops reading and saves the file.



---

### The Data Flow: Step-by-Step

**Recording a Macro:**

1. You tap the floating "Record" button in the game.
2. The Kotlin UI sends the `RECORD` signal via the Local Socket.
3. The C++ daemon opens the touchscreen's `/dev/input/eventX` file in `O_RDONLY` (Read-Only) mode.
4. It reads the stream of raw touch coordinates and timestamps, saving them to a `.bin` file in your app's private storage.
5. You tap "Stop", sending the `STOP` signal. The daemon closes the file.

**Playing a Macro:**

1. You tap the floating "Play" button.
2. The Kotlin UI sends the `PLAY /path/to/macro.bin` signal.
3. The C++ daemon opens the `.bin` file and opens the `/dev/input/eventX` node in `O_WRONLY` (Write-Only) mode.
4. It writes the exact same touch structs back to the kernel, perfectly replicating the timing and multi-touch data. The kernel thinks a real finger is touching the screen.
