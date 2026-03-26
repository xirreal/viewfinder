# Viewfinder

A shader debugging mod for [Iris](https://irisshaders.dev/) on Fabric that exposes an HTTP API and in-game commands for external tools and scripts to inspect, test, and automate shader development workflows.

## Features

- **Shader reload and error list** - trigger shader reloads and capture compilation errors
- **GPU timing metrics** - collect per-pass GPU timing data (collects multiple frames to average out noise)
- **Texture dumping** - export textures as PNG or raw binary by name (colortex0, or custom texture)
- **SSBO buffer dumping** - dump shader storage buffer objects to disk for analysis
- **Screenshot scheduling** - schedule screenshots with configurable frame delay (for PT)
- **Patched shader listing** - find which patched shader errored programmatically by listing all and searching for "errored"
- 
## Installation

1. Install [Fabric Loader](https://fabricmc.net/) (≥ 0.18.4) for Minecraft 26.1
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and [Iris](https://modrinth.com/mod/iris) (which requires [Sodium](https://modrinth.com/mod/sodium))
3. Drop the Viewfinder `.jar` into your `mods/` folder

## Usage

### In-game commands

All commands are registered under `/viewfinder`:

| Command | Description |
|---|---|
| `/viewfinder status` | Show current shaderpack name, error count, and API address |
| `/viewfinder reload` | Reload shaders and report any compilation errors |
| `/viewfinder errors` | List all captured shader errors |
| `/viewfinder screenshot [frames]` | Take a screenshot (optionally delayed by N frames) |
| `/viewfinder screenshot result` | Show the path of the last screenshot |
| `/viewfinder metrics` | Display per-pass GPU timings |
| `/viewfinder ssbo list` | List active SSBO buffers |
| `/viewfinder ssbo dump <index>` | Dump an SSBO buffer to `ssbo_dumps/` |
| `/viewfinder texture list` | List available colortex and custom textures |
| `/viewfinder texture dump name <name> [raw]` | Dump a texture by name (PNG or raw) |
| `/viewfinder texture dump id <id> [raw]` | Dump a texture by GL id (PNG or raw) |
| `/viewfinder patched_shaders` | List Iris patched shader files |

### HTTP API

Viewfinder starts an HTTP server on `localhost:7150`. A full OpenAPI 3.0 spec is available at `GET /`.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | OpenAPI 3.0 specification |
| `GET` | `/status` | Shaderpack status (loaded pack name) |
| `POST` | `/reload` | Reload shaders, returns success and errors |
| `GET` | `/errors` | List captured shader errors |
| `POST` | `/screenshot?frames=N` | Schedule a screenshot (default: next frame) |
| `GET` | `/screenshot/result` | Path to the last saved screenshot |
| `GET` | `/metrics` | GPU timing metrics |
| `GET` | `/list-ssbo` | List active SSBO buffers |
| `POST` | `/ssbo?index=N` | Dump SSBO at given index |
| `GET` | `/list-textures` | List available textures |
| `POST` | `/texture?name=...&raw=bool` | Dump texture by name |
| `POST` | `/texture?id=...&raw=bool` | Dump texture by GL id |
| `GET` | `/patched_shaders` | List patched shader files |

All responses are JSON. Dumped files (textures, SSBOs, screenshots) are written to subdirectories inside the Minecraft game directory.
