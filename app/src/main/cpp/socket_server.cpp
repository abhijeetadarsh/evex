#include "socket_server.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#define LOG_TAG "MacroDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

SocketServer::SocketServer() = default;

SocketServer::~SocketServer() { shutdown(); }

bool SocketServer::start(int port) {
  serverFd_ = socket(AF_INET, SOCK_STREAM, 0);
  if (serverFd_ < 0) {
    LOGE("Failed to create TCP socket: %s", strerror(errno));
    return false;
  }

  // Allow port reuse so quick restarts don't fail with EADDRINUSE
  int opt = 1;
  setsockopt(serverFd_, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

  struct sockaddr_in addr {};
  addr.sin_family = AF_INET;
  addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK); // 127.0.0.1 only
  addr.sin_port = htons(port);

  if (bind(serverFd_, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
    LOGE("Failed to bind TCP port %d: %s", port, strerror(errno));
    close(serverFd_);
    serverFd_ = -1;
    return false;
  }

  if (listen(serverFd_, 1) < 0) {
    LOGE("Failed to listen: %s", strerror(errno));
    close(serverFd_);
    serverFd_ = -1;
    return false;
  }

  LOGI("TCP server listening on 127.0.0.1:%d", port);
  return true;
}

bool SocketServer::acceptClient() {
  if (serverFd_ < 0)
    return false;

  LOGI("Waiting for client connection...");
  clientFd_ = accept(serverFd_, nullptr, nullptr);
  if (clientFd_ < 0) {
    LOGE("Failed to accept client: %s", strerror(errno));
    return false;
  }

  LOGI("Client connected (fd=%d)", clientFd_);
  return true;
}

bool SocketServer::sendMessage(const std::string &message) {
  if (clientFd_ < 0)
    return false;

  std::string msg = message + "\n";
  ssize_t sent = write(clientFd_, msg.c_str(), msg.length());
  if (sent < 0) {
    LOGE("Failed to send message: %s", strerror(errno));
    return false;
  }
  return true;
}

std::string SocketServer::receiveCommand() {
  if (clientFd_ < 0)
    return "";

  std::string result;
  char buf[1];

  // Read one byte at a time until newline (simple but reliable)
  while (true) {
    ssize_t n = read(clientFd_, buf, 1);
    if (n <= 0) {
      if (n == 0) {
        LOGI("Client disconnected");
      } else {
        LOGE("Socket read error: %s", strerror(errno));
      }
      clientFd_ = -1;
      return ""; // Empty string signals disconnection
    }
    if (buf[0] == '\n')
      break;
    result += buf[0];
  }

  // Strip trailing \r if present
  if (!result.empty() && result.back() == '\r') {
    result.pop_back();
  }

  LOGI("Received command: %s", result.c_str());
  return result;
}

void SocketServer::disconnectClient() {
  if (clientFd_ >= 0) {
    close(clientFd_);
    clientFd_ = -1;
    LOGI("Client disconnected");
  }
}

void SocketServer::shutdown() {
  disconnectClient();
  if (serverFd_ >= 0) {
    close(serverFd_);
    serverFd_ = -1;
    LOGI("TCP server shut down");
  }
}

bool SocketServer::isClientConnected() const { return clientFd_ >= 0; }
