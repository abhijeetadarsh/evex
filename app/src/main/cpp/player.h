#pragma once

#include <atomic>
#include <functional>
#include <string>


// Plays back recorded input_event structs from a binary file to a
// /dev/input/eventX node
class Player {
public:
  using StatusCallback = std::function<void(const std::string &)>;

  Player();
  ~Player();

  // Play back the macro file through the given device node
  // loopCount: 1 = play once, N = play N times, 0 = infinite loop
  // This blocks until playback is complete or stop() is called
  bool play(const std::string &devicePath, const std::string &macroFile,
            int loopCount = 1);

  // Signal the playback loop to stop
  void stop();

  // Check if currently playing
  bool isPlaying() const;

  // Set playback speed multiplier (0.5 = half speed, 2.0 = double speed)
  void setSpeed(float speedMultiplier);

  // Get current speed
  float getSpeed() const;

  // Set a callback for status updates
  void setStatusCallback(StatusCallback cb);

private:
  std::atomic<bool> playing_{false};
  std::atomic<float> speed_{1.0f};
  StatusCallback statusCb_;
};
