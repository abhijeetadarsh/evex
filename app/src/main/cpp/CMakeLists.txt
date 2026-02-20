cmake_minimum_required(VERSION 3.22.1)
project("macro_daemon" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Build as a standalone executable
add_executable(macro_daemon
    main.cpp
    device_discovery.cpp
    socket_server.cpp
    recorder.cpp
    player.cpp
)

target_link_libraries(macro_daemon
    log
)

# ── Package the executable into the APK ──────────────────────────────
# Android's AGP only packages SHARED library CMake targets, not executables.
# However, AGP ALWAYS packages files from src/main/jniLibs/<abi>/.
# So we copy the built executable there with a lib*.so name after every build.
set(JNILIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

add_custom_command(TARGET macro_daemon POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E make_directory "${JNILIBS_DIR}"
    COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:macro_daemon>
        "${JNILIBS_DIR}/libmacro_daemon.so"
    COMMENT "Copying daemon to jniLibs for APK packaging"
)
