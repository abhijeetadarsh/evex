#pragma once

// Command protocol between Kotlin UI and C++ daemon
// All commands are newline-terminated ASCII strings over UNIX domain socket

namespace protocol {

// ─── UI → Daemon Commands ────────────────────────────────────
constexpr const char *CMD_START_REC = "START_REC";  // + space + filepath
constexpr const char *CMD_STOP_REC = "STOP_REC";
constexpr const char *CMD_PLAY = "PLAY";  // + space + filepath
constexpr const char *CMD_PLAY_LOOP =
    "PLAY_LOOP";  // + space + count + space + filepath (0 = infinite)
constexpr const char *CMD_STOP_PLAY = "STOP_PLAY";
constexpr const char *CMD_SET_SPEED =
    "SET_SPEED";  // + space + multiplier (e.g. "0.5", "1.0", "2.0")
constexpr const char *CMD_LIST_DEVICES = "LIST_DEVICES";
constexpr const char *CMD_PING = "PING";
constexpr const char *CMD_QUIT = "QUIT";

// ─── Daemon → UI Responses ───────────────────────────────────
constexpr const char *RSP_OK = "OK";
constexpr const char *RSP_ERROR = "ERROR";
constexpr const char *RSP_PONG = "PONG";
constexpr const char *RSP_REC_STARTED = "REC_STARTED";
constexpr const char *RSP_REC_FIRST_EVENT = "REC_FIRST_EVENT";
constexpr const char *RSP_REC_STOPPED = "REC_STOPPED";
constexpr const char *RSP_PLAY_STARTED = "PLAY_STARTED";
constexpr const char *RSP_PLAY_DONE = "PLAY_DONE";
constexpr const char *RSP_PLAY_STOPPED = "PLAY_STOPPED";
constexpr const char *RSP_PLAY_LOOP_ITER =
    "PLAY_LOOP_ITER";  // + space + current iteration
constexpr const char *RSP_SPEED_SET = "SPEED_SET";  // + space + new speed
constexpr const char *RSP_DEVICE = "DEVICE";  // + space + path + space + name

// ─── Socket Configuration ────────────────────────────────────
// TCP loopback port (127.0.0.1 only, avoids SELinux cross-context issues)
constexpr int DAEMON_PORT = 52398;

}  // namespace protocol
