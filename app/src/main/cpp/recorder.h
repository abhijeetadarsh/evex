#pragma once

#include <atomic>
#include <functional>
#include <string>


// Records raw input_event structs from a /dev/input/eventX node to a binary
// file
class Recorder {
public:
  using StatusCallback = std::function<void(const std::string &)>;

  Recorder();
  ~Recorder();

  // Start recording from the given device to the output file
  // This blocks until stop() is called (run on a separate thread)
  bool start(const std::string &devicePath, const std::string &outputFile);

  // Signal the recording loop to stop
  void stop();

  // Check if currently recording
  bool isRecording() const;

  // Set a callback for status updates
  void setStatusCallback(StatusCallback cb);

private:
  std::atomic<bool> recording_{false};
  StatusCallback statusCb_;
};
