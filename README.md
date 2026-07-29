# Cosmic IDE plugins

This repository contains dynamically installable plugins for Cosmic IDE and the public
`plugins.json` index consumed by **Settings > Extensions > Plugins**.

## Included plugins

- **Rust Support** — rust-analyzer LSP integration, Cargo project creation/detection, and Cargo
  project commands.

On its first install, Cosmic offers to open an interactive terminal running:

```sh
pacman -S --needed rust rust-analyzer
```

The package command runs only after the user confirms the terminal handoff.

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
