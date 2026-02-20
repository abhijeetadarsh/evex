#include "recorder.h"

#include <android/log.h>
#include <fcntl.h>
#include <linux/input.h>
#include <unistd.h>

#include <cerrno>
#include <cstring>

#define LOG_TAG "MacroDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

Recorder::Recorder() = default;
Recorder::~Recorder() { stop(); }

void Recorder::setStatusCallback(StatusCallback cb) {
  statusCb_ = std::move(cb);
}

bool Recorder::start(const std::string &devicePath,
                     const std::string &outputFile) {
  // Open the input device for reading
  int inputFd = open(devicePath.c_str(), O_RDONLY);
  if (inputFd < 0) {
    LOGE("Failed to open input device %s: %s", devicePath.c_str(),
         strerror(errno));
    if (statusCb_)
      statusCb_("ERROR Failed to open input device");
    return false;
  }

  // Open the output binary file
  int outFd = open(outputFile.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (outFd < 0) {
    LOGE("Failed to create output file %s: %s", outputFile.c_str(),
         strerror(errno));
    close(inputFd);
    if (statusCb_)
      statusCb_("ERROR Failed to create output file");
    return false;
  }

  recording_ = true;
  LOGI("Recording started: %s -> %s", devicePath.c_str(), outputFile.c_str());
  if (statusCb_)
    statusCb_("REC_STARTED");

  struct input_event ev;
  long eventCount = 0;

  // Use select() with a timeout so we can check the stop flag
  while (recording_) {
    fd_set readfds;
    FD_ZERO(&readfds);
    FD_SET(inputFd, &readfds);

    struct timeval timeout;
    timeout.tv_sec = 0;
    timeout.tv_usec = 100000; // 100ms timeout for checking stop flag

    int ret = select(inputFd + 1, &readfds, nullptr, nullptr, &timeout);
    if (ret < 0) {
      if (errno == EINTR)
        continue;
      LOGE("select() error: %s", strerror(errno));
      break;
    }

    if (ret == 0)
      continue; // Timeout — loop back to check recording_ flag

    ssize_t n = read(inputFd, &ev, sizeof(ev));
    if (n == sizeof(ev)) {
      write(outFd, &ev, sizeof(ev));
      eventCount++;

      // Flush to disk every 100 events to prevent data loss on kill
      if (eventCount % 100 == 0) {
        fsync(outFd);
      }
    } else if (n < 0) {
      if (errno == EINTR)
        continue;
      LOGE("Read error: %s", strerror(errno));
      break;
    }
  }

  fsync(outFd); // Final flush before close
  close(inputFd);
  close(outFd);
  recording_ = false;

  LOGI("Recording stopped. %ld events captured.", eventCount);
  if (statusCb_)
    statusCb_("REC_STOPPED");
  return true;
}

void Recorder::stop() { recording_ = false; }

bool Recorder::isRecording() const { return recording_; }
