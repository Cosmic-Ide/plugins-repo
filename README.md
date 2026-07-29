# Cosmic IDE plugins

This repository contains dynamically installable plugins for Cosmic IDE and the public
`plugins.json` index consumed by **Settings > Extensions > Plugins**.

Each marketplace entry provides two levels of copy:

- `description` is a concise, plain-text summary used on search result cards.
- `detailedDescription` is the full extension detail page and supports Markdown.

Keep the short description to roughly one sentence. The detailed description can use headings, lists,
links, inline code, code blocks, and other standard Markdown. For consistency, include both fields
in the repository index and the plugin's bundled `plugin.json` metadata.

## Included plugins

- **Rust Support** — rust-analyzer LSP integration, Cargo project creation/detection, and Cargo
  project commands.
- **Clangd Support** — clangd code intelligence for C, C++, Objective-C, Objective-C++, and CUDA.
- **CMake Support** — CMake project creation/detection and configure, build, run, test, install,
  and clean commands.

On its first install, Cosmic offers to open an interactive terminal running:

```sh
pacman -S --needed rust rust-analyzer gcc
```

The package command runs only after the user confirms the terminal handoff.
It is declared by `RustPlugin.setupActions`, keeping environment setup separate from the Cargo
project creation form.

Clangd Support installs the `clang` package. CMake Support remains independent and installs
`cmake` and `make`; its configure commands export `compile_commands.json` for clangd.

## Build

The plugin build compiles against the sibling Cosmic IDE checkout without packaging Cosmic's API
classes into the plugin APK:

```sh
./gradlew :plugins:rust-support:packageProdReleasePlugin \
  -PcosmicIdeDir=/path/to/Cosmic-IDE
```

The installable ZIP is written below `artifacts/`. Update its SHA-256 in `plugins.json` whenever
the bundle changes.

Plugin bundles contain a `plugin.json` manifest and one loadable artifact named `plugin.apk`.
