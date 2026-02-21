#include "player.h"

#include <android/log.h>
#include <fcntl.h>
#include <linux/input.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>


#define LOG_TAG "MacroDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

Player::Player() = default;
Player::~Player() {
  stop();
}

void Player::setStatusCallback(StatusCallback cb) {
  statusCb_ = std::move(cb);
}

void Player::setSpeed(float speedMultiplier) {
  if (speedMultiplier > 0.0f && speedMultiplier <= 10.0f) {
    speed_ = speedMultiplier;
    LOGI("Playback speed set to %.2fx", speedMultiplier);
  }
}

float Player::getSpeed() const {
  return speed_;
}

// Calculate time difference in microseconds between two input_event timestamps
static long long timeDiffUs(const struct input_event &a,
                            const struct input_event &b) {
  long long diffSec = (long long)b.time.tv_sec - (long long)a.time.tv_sec;
  long long diffUsec = (long long)b.time.tv_usec - (long long)a.time.tv_usec;
  return diffSec * 1000000LL + diffUsec;
}

bool Player::play(const std::string &devicePath, const std::string &macroFile,
                  int loopCount) {
  // Validate macro file exists before doing anything
  if (access(macroFile.c_str(), F_OK) != 0) {
    LOGE("Macro file does not exist: %s", macroFile.c_str());
    if (statusCb_) statusCb_("ERROR Macro file not found: " + macroFile);
    return false;
  }

  // Check the file has content (at least one input_event)
  struct stat fileStat;
  if (stat(macroFile.c_str(), &fileStat) == 0 &&
      fileStat.st_size < (off_t)sizeof(struct input_event)) {
    LOGE("Macro file is empty or corrupted: %s (%ld bytes)", macroFile.c_str(),
         (long)fileStat.st_size);
    if (statusCb_) statusCb_("ERROR Macro file is empty or corrupted");
    return false;
  }

  // Open the device node for writing
  int devFd = open(devicePath.c_str(), O_WRONLY);
  if (devFd < 0) {
    LOGE("Failed to open device %s for writing: %s", devicePath.c_str(),
         strerror(errno));
    if (statusCb_) statusCb_("ERROR Failed to open device for writing");
    return false;
  }

  playing_ = true;
  LOGI("Playback started: %s -> %s (loops=%d, speed=%.2f)", macroFile.c_str(),
       devicePath.c_str(), loopCount, speed_.load());
  if (statusCb_) statusCb_("PLAY_STARTED");

  int iteration = 0;
  bool infinite = (loopCount == 0);

  while (playing_ && (infinite || iteration < loopCount)) {
    // Open the macro file fresh each iteration
    int fileFd = open(macroFile.c_str(), O_RDONLY);
    if (fileFd < 0) {
      LOGE("Failed to open macro file %s: %s", macroFile.c_str(),
           strerror(errno));
      if (statusCb_) statusCb_("ERROR Failed to open macro file");
      break;
    }

    struct input_event ev;
    struct input_event prevEv;
    bool firstEvent = true;
    long eventCount = 0;

    while (playing_) {
      ssize_t n = read(fileFd, &ev, sizeof(ev));
      if (n < (ssize_t)sizeof(ev)) {
        // End of file or partial read
        break;
      }

      // Replicate the original timing, adjusted for speed
      if (!firstEvent) {
        long long delayUs = timeDiffUs(prevEv, ev);
        if (delayUs > 0 && delayUs < 10000000LL) {  // Cap at 10 seconds
          // Apply speed multiplier: higher speed = shorter delay
          float currentSpeed = speed_.load();
          long long adjustedDelay =
              static_cast<long long>(delayUs / currentSpeed);
          if (adjustedDelay > 0) {
            usleep(static_cast<useconds_t>(adjustedDelay));
          }
        }
      }

      // Save the original event (with its valid timestamp) for the next
      // iteration
      prevEv = ev;

      // Clear the timestamp before injecting — let the kernel set it
      ev.time.tv_sec = 0;
      ev.time.tv_usec = 0;

      ssize_t written = write(devFd, &ev, sizeof(ev));
      if (written < 0) {
        LOGE("Failed to write event: %s", strerror(errno));
      }

      firstEvent = false;
      eventCount++;
    }

    close(fileFd);
    iteration++;

    if (playing_ && (infinite || iteration < loopCount)) {
      // Notify about loop iteration
      if (statusCb_) {
        statusCb_("PLAY_LOOP_ITER " + std::to_string(iteration));
      }
      LOGI("Loop iteration %d completed (%ld events)", iteration, eventCount);
      // Small pause between loops
      usleep(100000);  // 100ms
    } else {
      LOGI("Playback iteration %d finished. %ld events replayed.", iteration,
           eventCount);
    }
  }

  close(devFd);
  bool wasStopped = !playing_;
  playing_ = false;

  if (wasStopped) {
    if (statusCb_) statusCb_("PLAY_STOPPED");
  } else {
    if (statusCb_) statusCb_("PLAY_DONE");
  }
  return true;
}

void Player::stop() {
  playing_ = false;
}

bool Player::isPlaying() const {
  return playing_;
}
