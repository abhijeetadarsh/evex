#pragma once

#include <string>

// TCP loopback socket server for IPC with the Kotlin frontend.
// Uses 127.0.0.1 to avoid SELinux restrictions on UNIX domain sockets
// between the untrusted_app and su contexts.
class SocketServer {
public:
  SocketServer();
  ~SocketServer();

  // Start listening on 127.0.0.1:<port>
  bool start(int port);

  // Accept a single client connection (blocking)
  bool acceptClient();

  // Send a newline-terminated message to the connected client
  bool sendMessage(const std::string &message);

  // Receive a newline-terminated command from the client (blocking)
  std::string receiveCommand();

  // Disconnect the current client
  void disconnectClient();

  // Shut down the server entirely
  void shutdown();

  bool isClientConnected() const;

private:
  int serverFd_ = -1;
  int clientFd_ = -1;
};
