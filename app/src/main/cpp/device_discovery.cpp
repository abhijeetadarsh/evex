#include "device_discovery.h"

#include <android/log.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <cstring>
#include <fstream>
#include <regex>
#include <sstream>

#define LOG_TAG "MacroDaemon"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

bool DeviceDiscovery::isTouchDevice(const std::string &path) {
  int fd = open(path.c_str(), O_RDONLY);
  if (fd < 0)
    return false;

  // Check if device supports ABS_MT_POSITION_X (multi-touch)
  unsigned long absBits[(ABS_MAX + 1) / (sizeof(unsigned long) * 8) + 1] = {0};
  if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(absBits)), absBits) >= 0) {
    // Check for ABS_MT_POSITION_X (0x35)
    int bit = ABS_MT_POSITION_X;
    if (absBits[bit / (sizeof(unsigned long) * 8)] &
        (1UL << (bit % (sizeof(unsigned long) * 8)))) {
      close(fd);
      return true;
    }
  }

  close(fd);
  return false;
}

std::vector<DeviceDiscovery::InputDevice> DeviceDiscovery::findTouchDevices() {
  std::vector<InputDevice> devices;

  // Method 1: Parse /proc/bus/input/devices
  std::ifstream proc("/proc/bus/input/devices");
  if (proc.is_open()) {
    std::string line;
    std::string currentName;
    std::string currentHandlers;

    while (std::getline(proc, line)) {
      if (line.empty()) {
        // End of a device block — check if it's a touch device
        if (!currentHandlers.empty() && !currentName.empty()) {
          // Extract eventX from handlers
          std::regex eventRegex("event(\\d+)");
          std::smatch match;
          if (std::regex_search(currentHandlers, match, eventRegex)) {
            std::string eventPath = "/dev/input/event" + match[1].str();
            if (isTouchDevice(eventPath)) {
              devices.push_back({eventPath, currentName});
              LOGI("Found touch device: %s (%s)", eventPath.c_str(),
                   currentName.c_str());
            }
          }
        }
        currentName.clear();
        currentHandlers.clear();
      } else if (line.substr(0, 2) == "N:") {
        // Name line: N: Name="device_name"
        auto start = line.find('"');
        auto end = line.rfind('"');
        if (start != std::string::npos && end != std::string::npos &&
            start < end) {
          currentName = line.substr(start + 1, end - start - 1);
        }
      } else if (line.substr(0, 2) == "H:") {
        currentHandlers = line;
      }
    }

    // Handle last device block
    if (!currentHandlers.empty() && !currentName.empty()) {
      std::regex eventRegex("event(\\d+)");
      std::smatch match;
      if (std::regex_search(currentHandlers, match, eventRegex)) {
        std::string eventPath = "/dev/input/event" + match[1].str();
        if (isTouchDevice(eventPath)) {
          devices.push_back({eventPath, currentName});
          LOGI("Found touch device: %s (%s)", eventPath.c_str(),
               currentName.c_str());
        }
      }
    }
  }

  // Method 2: Fallback — scan /dev/input/ directly
  if (devices.empty()) {
    LOGI("Fallback: scanning /dev/input/ directly");
    DIR *dir = opendir("/dev/input/");
    if (dir) {
      struct dirent *entry;
      while ((entry = readdir(dir)) != nullptr) {
        std::string name = entry->d_name;
        if (name.find("event") == 0) {
          std::string path = "/dev/input/" + name;
          if (isTouchDevice(path)) {
            // Get device name via ioctl
            char devName[256] = "Unknown";
            int fd = open(path.c_str(), O_RDONLY);
            if (fd >= 0) {
              ioctl(fd, EVIOCGNAME(sizeof(devName)), devName);
              close(fd);
            }
            devices.push_back({path, devName});
            LOGI("Found touch device (fallback): %s (%s)", path.c_str(),
                 devName);
          }
        }
      }
      closedir(dir);
    }
  }

  if (devices.empty()) {
    LOGE("No touch devices found!");
  }

  return devices;
}

std::string DeviceDiscovery::getBestTouchDevice() {
  auto devices = findTouchDevices();
  if (devices.empty())
    return "";
  return devices[0].path;
}
