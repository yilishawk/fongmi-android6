cmake_minimum_required(VERSION 3.22.1)

# C only, deliberately: libc_shim.c must stay independent of the C++ runtime, because it
# is loaded before anything else and a C++ dependency would add a load-order requirement
# of its own. (Upstream webhtv also builds exo_dovi_renderer here; this project does not
# need it — TV-fongmi routes Dolby Vision through media3's own output policy.)
project(tvshim LANGUAGES C)

# ---------------------------------------------------------------------------
# tvshim — bionic compatibility shim for Android 6 (API 23).
#
# Supplies the bionic symbols that appeared in Android 7.0 but are referenced by the
# prebuilt native libraries this APK ships. Android 6's libc exports none of them, and
# System.load()/loadLibrary() resolve with RTLD_NOW, so a single unresolvable symbol
# makes the whole dlopen() fail.
#
# What THIS project actually needs was measured, not assumed — by auditing the APK's own
# native libraries against the NDK's API-23 stub libraries with webhtv's
# apk-check/audit_sym_versions.py (it is version-node aware, which matters: a
# `foo@LIBC_N` reference is NOT satisfied by an unversioned `foo` definition):
#
#   armeabi-v7a
#     libmpv.so         __write_chk, getifaddrs, freeifaddrs, fseeko64   (@LIBC_N)
#     libavcodec.so     __aeabi_memcpy/memmove/memset/memclr and their
#     libavfilter.so      4- and 8-suffixed forms                        (@LIBC_N)
#     libavformat.so    (same 12)
#
#   arm64-v8a
#     libmpv.so         __write_chk, getifaddrs, freeifaddrs             (@LIBC_N)
#                       — no fseeko64 (off_t is already 64-bit) and no __aeabi_mem*
#                       (aarch64 has no ARM EABI runtime helpers at all)
#
# The 7 FFmpeg shared libraries themselves are NOT a shim problem — libmpv.so lists them
# in its DT_NEEDED and they simply have to exist. They are packaged from
# app/src/main/jniLibs/<abi>/, taken from the same FongMi/media fork revision as
# libmpv.so (libraries/decoder_ffmpeg/src/main/jniLibs/).
#
# Loaded by com.fongmi.android.tv.utils.NativeCompat.loadLibcShim(), called from App
# before any player is created. See libc_shim.c for the full rationale.
#
# Built for every ABI on purpose: each ABI needs only a subset, so the extra definitions
# are inert, and it keeps System.loadLibrary("tvshim") valid regardless of ABI. It is
# only *loaded* on API < 24.
# ---------------------------------------------------------------------------
add_library(tvshim SHARED libc_shim.c)

target_compile_options(tvshim PRIVATE
        -Wall -Wextra
        # Group 4 (__aeabi_mem*) implements its loops in C and must NOT have them
        # recognised as calls to memcpy/memmove/memset: on ARM clang lowers such a call to
        # the matching __aeabi_* name, which is the name being defined — the forwarder
        # would call itself. See the comment beside those definitions in libc_shim.c.
        -fno-builtin-memcpy
        -fno-builtin-memmove
        -fno-builtin-memset)
target_link_options(tvshim PRIVATE
        -Wl,--no-undefined
        # Stamp DT_FLAGS_1 | DF_1_GLOBAL into the .so.
        #
        # libmpv.so does NOT list us in its DT_NEEDED — it just has undefined
        # __write_chk@LIBC_N and friends that it expects libc to provide. So it can only
        # find ours if this library is in the *global* scope at the time libmpv is
        # relocated. bionic's dlopen() resolves symbols against the global group plus the
        # library's own dependency closure, and RTLD_LOCAL (the default) puts a freshly
        # dlopen'ed library in a *local* group only.
        #
        # Rather than depend on whatever flags System.loadLibrary() happens to pass
        # (an implementation detail that has changed between releases), mark the
        # library itself as global. bionic's load_library() reads this tag straight
        # off the ELF and find_libraries() then puts any not-yet-linked library with
        # that bit set into the global group of every namespace.
        #
        # So the promotion happens on load, deterministically, on every Android
        # version. (Verify with: llvm-readelf -d libtvshim.so | grep FLAGS_1
        #  -> should print "NOW GLOBAL".)
        -Wl,-z,global
        "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/libc_shim.map")
# libc is linked implicitly by the NDK toolchain; --no-undefined is satisfied by it.

# ---------------------------------------------------------------------------
# vulkan_stub.c is deliberately NOT a target in this file.
#
# It builds libvulkan.so, which libmpv.so names in its DT_NEEDED — Vulkan arrived in
# API 24, so Android 6 has no such library and the loader fails before resolving any
# symbol (63 vk* entry points in this project's libmpv.so). Supplying it means supplying
# a *file*, which is why the libc shim above cannot help with this one.
#
# Every target in this file is packaged by AGP into the APK's lib/<abi>/. That is exactly
# where this library must NOT go: a library under lib/ joins the app's native-library
# search path, so on a device that does have Vulkan it would shadow the system's real
# libvulkan.so for the whole app. It ships in the asset bundle instead, and
# NativeCompat.preloadVulkanStub() loads it by absolute path, only below API 24.
#
# So the built libvulkan.so is committed at app/src/main/assets/vulkan-libs/<abi>/ and
# vulkan_stub.c is kept next to this file as its source of truth. Rebuild both ABIs with:
#
#     bash .gradle-user/build_vulkan_stub.sh
#
# then confirm it still exports everything libmpv.so asks for by re-running
# apk-check/audit_sym_versions.py (see the header of that script).
# ---------------------------------------------------------------------------
