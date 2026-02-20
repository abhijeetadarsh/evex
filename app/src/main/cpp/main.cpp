#include "device_discovery.h"
#include "player.h"
#include "protocol.h"
#include "recorder.h"
#include "socket_server.h"

#include <android/log.h>
#include <unistd.h>

#include <csignal>
#include <cstring>
#include <string>
#include <thread>

#define LOG_TAG "MacroDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static SocketServer server;
static Recorder recorder;
static Player player;
static std::string touchDevicePath;
static std::thread workerThread;

// Extract argument from a command string: "CMD arg" -> "arg"
static std::string extractArg(const std::string &cmd) {
  auto pos = cmd.find(' ');
  if (pos == std::string::npos)
    return "";
  return cmd.substr(pos + 1);
}

// Extract command name: "CMD arg" -> "CMD"
static std::string extractCmd(const std::string &cmd) {
  auto pos = cmd.find(' ');
  if (pos == std::string::npos)
    return cmd;
  return cmd.substr(0, pos);
}

static void handleCommand(const std::string &rawCmd) {
  std::string cmd = extractCmd(rawCmd);
  std::string arg = extractArg(rawCmd);

  if (cmd == protocol::CMD_PING) {
    server.sendMessage(protocol::RSP_PONG);
  } else if (cmd == protocol::CMD_LIST_DEVICES) {
    auto devices = DeviceDiscovery::findTouchDevices();
    for (const auto &dev : devices) {
      server.sendMessage(std::string(protocol::RSP_DEVICE) + " " + dev.path +
                         " " + dev.name);
    }
    server.sendMessage(protocol::RSP_OK);
  } else if (cmd == protocol::CMD_START_REC) {
    if (recorder.isRecording()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Already recording");
      return;
    }
    if (player.isPlaying()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Currently playing");
      return;
    }
    if (arg.empty()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " No output path specified");
      return;
    }

    std::string outPath = arg;

    // Set status callback to forward to socket
    recorder.setStatusCallback(
        [](const std::string &status) { server.sendMessage(status); });

    // Start recording on a worker thread
    if (workerThread.joinable())
      workerThread.join();
    workerThread =
        std::thread([outPath]() { recorder.start(touchDevicePath, outPath); });

    // NOTE: Don't send REC_STARTED here — recorder.start() sends it via
    // callback
  } else if (cmd == protocol::CMD_STOP_REC) {
    if (!recorder.isRecording()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) + " Not recording");
      return;
    }
    recorder.stop();
    if (workerThread.joinable())
      workerThread.join();
    server.sendMessage(protocol::RSP_REC_STOPPED);
  } else if (cmd == protocol::CMD_PLAY) {
    if (player.isPlaying()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) + " Already playing");
      return;
    }
    if (recorder.isRecording()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Currently recording");
      return;
    }
    if (arg.empty()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " No macro file specified");
      return;
    }

    std::string macroPath = arg;

    player.setStatusCallback(
        [](const std::string &status) { server.sendMessage(status); });

    if (workerThread.joinable())
      workerThread.join();
    workerThread =
        std::thread([macroPath]() { player.play(touchDevicePath, macroPath); });

    // NOTE: Don't send PLAY_STARTED here — player.play() sends it via callback
  } else if (cmd == protocol::CMD_PLAY_LOOP) {
    // Format: PLAY_LOOP <count> <filepath>
    if (player.isPlaying()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) + " Already playing");
      return;
    }
    if (recorder.isRecording()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Currently recording");
      return;
    }
    // Parse count and filepath from arg
    auto spacePos = arg.find(' ');
    if (spacePos == std::string::npos) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Usage: PLAY_LOOP <count> <filepath>");
      return;
    }
    int loopCount = std::stoi(arg.substr(0, spacePos));
    std::string macroPathLoop = arg.substr(spacePos + 1);

    player.setStatusCallback(
        [](const std::string &status) { server.sendMessage(status); });

    if (workerThread.joinable())
      workerThread.join();
    workerThread = std::thread([macroPathLoop, loopCount]() {
      player.play(touchDevicePath, macroPathLoop, loopCount);
    });

    // NOTE: Don't send PLAY_STARTED here — player.play() sends it via callback
  } else if (cmd == protocol::CMD_SET_SPEED) {
    if (arg.empty()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) +
                         " Usage: SET_SPEED <multiplier>");
      return;
    }
    float speed = std::stof(arg);
    player.setSpeed(speed);
    server.sendMessage(std::string(protocol::RSP_SPEED_SET) + " " + arg);
  } else if (cmd == protocol::CMD_STOP_PLAY) {
    if (!player.isPlaying()) {
      server.sendMessage(std::string(protocol::RSP_ERROR) + " Not playing");
      return;
    }
    player.stop();
    if (workerThread.joinable())
      workerThread.join();
    server.sendMessage(protocol::RSP_PLAY_STOPPED);
  } else if (cmd == protocol::CMD_QUIT) {
    recorder.stop();
    player.stop();
    if (workerThread.joinable())
      workerThread.join();
    server.sendMessage(protocol::RSP_OK);
    server.shutdown();
  } else {
    server.sendMessage(std::string(protocol::RSP_ERROR) +
                       " Unknown command: " + cmd);
  }
}

static void signalHandler(int sig) {
  LOGI("Received signal %d, shutting down...", sig);
  recorder.stop();
  player.stop();
  server.shutdown();
  _exit(0);
}

int main(int argc, char *argv[]) {
  LOGI("=== Macro Daemon Starting ===");

  // Handle termination signals gracefully
  signal(SIGTERM, signalHandler);
  signal(SIGINT, signalHandler);
  signal(SIGPIPE, SIG_IGN); // Ignore broken pipe

  // Step 1: Discover touch device
  LOGI("Discovering touch devices...");
  touchDevicePath = DeviceDiscovery::getBestTouchDevice();
  if (touchDevicePath.empty()) {
    LOGE("FATAL: No touch device found. Exiting.");
    return 1;
  }
  LOGI("Using touch device: %s", touchDevicePath.c_str());

  // Step 2: Start TCP socket server
  if (!server.start(protocol::DAEMON_PORT)) {
    LOGE("FATAL: Failed to start socket server. Exiting.");
    return 1;
  }

  // Step 3: Main loop — accept connections and process commands
  while (true) {
    LOGI("Waiting for client connection...");
    if (!server.acceptClient()) {
      LOGE("Failed to accept client, retrying...");
      continue;
    }

    // Command loop for this client
    while (server.isClientConnected()) {
      std::string cmd = server.receiveCommand();
      if (cmd.empty()) {
        // Client disconnected
        LOGI("Client disconnected, waiting for new connection...");
        break;
      }
      handleCommand(cmd);

      // Check if QUIT was received
      if (extractCmd(cmd) == protocol::CMD_QUIT) {
        LOGI("QUIT received, shutting down daemon.");
        return 0;
      }
    }

    // Clean up any active operations when client disconnects
    if (recorder.isRecording()) {
      recorder.stop();
      if (workerThread.joinable())
        workerThread.join();
    }
    if (player.isPlaying()) {
      player.stop();
      if (workerThread.joinable())
        workerThread.join();
    }
  }

  return 0;
}
