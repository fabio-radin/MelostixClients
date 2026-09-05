# Third-party software

This repository's own code is MIT-licensed (see `LICENSE`). It also builds against the
following third-party components, none of which are ours:

## linux-client

- **[Dear ImGui](https://github.com/ocornut/imgui)** — MIT License. Vendored as a git submodule
  at `linux-client/third_party/imgui` (see `.gitmodules`); not copied into this repository, so its
  own license and history stay with upstream. Used only for a small debug/status overlay
  (`F1` to toggle), not for the main lyrics view.
- **[SDL2](https://www.libsdl.org/)** and **SDL2_ttf** — zlib License. System libraries, linked at
  build time via `pkg-config` (see `linux-client/Makefile`); not vendored in this repository.
- **OpenSSL (libcrypto)** — Apache License 2.0 (OpenSSL 3.x) / OpenSSL License (older). System
  library, used only for HMAC-SHA256 in the optional password-authentication handshake
  (MelostixProtocol v1.1.0+). Linked via `pkg-config`, not vendored.

## windows-client

- **.NET / WPF (`System.Text.Json`, `System.Security.Cryptography`)** — MIT License, part of the
  .NET base class library. No third-party NuGet packages are referenced in
  `MelostixClient.csproj`.

## python-client

- **[windows-curses](https://pypi.org/project/windows-curses/)** — MIT License. Only installed on
  Windows (see the environment marker in `python-client/requirements.txt`); on Linux/macOS the
  standard library's own `curses` module is used and nothing extra is installed.

## android-tablet-client

- Android Gradle Plugin and Kotlin Gradle Plugin — standard build tooling (Apache License 2.0),
  declared in `android-tablet-client/gradle/libs.versions.toml`. No other third-party libraries
  are referenced in `app-tablet/build.gradle.kts`.

---

If you add a new dependency to any client, add it here too, in the section for that client.
