#pragma once

#include <string>
#include <utility>
#include <vector>


// Discovers the correct /dev/input/eventX touch device node
class DeviceDiscovery {
public:
  struct InputDevice {
    std::string path; // e.g. /dev/input/event4
    std::string name; // e.g. "sec_touchscreen"
  };

  // Scan /proc/bus/input/devices for touch-capable nodes
  static std::vector<InputDevice> findTouchDevices();

  // Get the "best" touch device path (first ABS_MT capable device)
  static std::string getBestTouchDevice();

private:
  // Verify device supports ABS_MT_POSITION_X via ioctl
  static bool isTouchDevice(const std::string &path);
};
