# Viewfinder

A shader debugging mod for [Iris](https://irisshaders.dev/) on Fabric that exposes an MCP server and in-game commands for AI agents to inspect, test, and automate shader development workflows.

## Features

- **Shader reload and error list** - trigger shader reloads and capture compilation errors
- **Queued atomic workflows** - run up to 64 ordered actions without interleaving between MCP clients or agents
- **Shaderpack and config switching** - list installed packs, select one, reset or apply options, and reload
- **On-demand GPU profiles** - collect hierarchical per-pass timings over a fixed frame window without permanent query overhead
- **Pipeline and program inspection** - observe nested Iris passes and reflect linked program uniforms, UBOs, and SSBOs
- **Texture dumping** - inspect or export main/alternate colortex, depth, shadow, noise, and custom textures
- **SSBO buffer dumping** - dump shader storage buffer objects to disk for analysis
- **Screenshot scheduling** - schedule screenshots with configurable frame delay (for PT)
- **Patched shader listing** - find which patched shader errored programmatically by listing all and searching for "errored"
- **Interactive chat output** - copy paths, open dumped files, and insert follow-up commands from chat
- **Agent diagnostics snapshot** - one JSON payload with shaderpack, render, errors, metrics, textures, SSBOs, and suggested next actions
- **Bounded inspection probes** - inspect texture stats/pixels and SSBO byte previews before dumping large GPU resources
- **Pass output capture** - export framebuffer attachments at the end of a selected Iris pass
- **Shader mutation** - edit directory-pack sources or options and reload with structured diagnostics
- **Deterministic scenes** - position the authoritative singleplayer player, set time/weather, and freeze or step ticks

## Installation

1. Install [Fabric Loader](https://fabricmc.net/) for Minecraft 26.1 or 26.2
2. Install [Fabric API](https://modrinth.com/mod/fabric-api) and [Iris](https://modrinth.com/mod/iris) (which requires [Sodium](https://modrinth.com/mod/sodium))
3. Drop the Viewfinder `.jar` matching your Minecraft version into your `mods/` folder

## Development

Stonecutter builds both supported versions from the shared source tree:

```bash
./gradlew build
```

The release jars are written to `versions/26.1/build/libs/` and `versions/26.2/build/libs/`. Use `./gradlew "Set active project to 26.1"` or `./gradlew "Set active project to 26.2"` when editing version-dependent source.

## Usage

### In-game commands

All commands are registered under `/viewfinder`:

| Command | Description |
|---|---|
| `/viewfinder status` | Show current shaderpack name, error count, and MCP address |
| `/viewfinder snapshot` | Summarize the agent diagnostics snapshot |
| `/viewfinder reload` | Reload shaders and report any compilation errors |
| `/viewfinder errors` | List all captured shader errors |
| `/viewfinder errors all` | List the full captured error buffer |
| `/viewfinder errors clear` | Clear captured shader errors |
| `/viewfinder screenshot [frames]` | Take a screenshot (optionally delayed by N frames) |
| `/viewfinder screenshot result` | Show the path of the last screenshot |
| `/viewfinder metrics` | Display the last captured GPU timings |
| `/viewfinder metrics capture [frames]` | Profile the next fixed number of frames |
| `/viewfinder metrics reset` | Clear collected GPU timing samples |
| `/viewfinder ssbo list` | List active SSBO buffers |
| `/viewfinder ssbo inspect <index> [bytes]` | Preview SSBO bytes as hex, int, uint, and float values |
| `/viewfinder ssbo dump <index>` | Dump an SSBO buffer to `ssbo_dumps/` |
| `/viewfinder texture list` | List available colortex and custom textures |
| `/viewfinder texture inspect name <name> [samples]` | Probe texture metadata, samples, and channel statistics by name |
| `/viewfinder texture inspect id <id> [samples]` | Probe texture metadata, samples, and channel statistics by GL id |
| `/viewfinder texture sample name <name> <x> <y> [z]` | Read a specific texture pixel by name |
| `/viewfinder texture sample id <id> <x> <y> [z]` | Read a specific texture pixel by GL id |
| `/viewfinder texture dump name <name> [raw]` | Dump a texture by name (PNG or raw) |
| `/viewfinder texture dump id <id> [raw]` | Dump a texture by GL id (PNG or raw) |
| `/viewfinder patched_shaders` | List Iris patched shader files |

Chat output uses clickable controls where Minecraft supports them: dumped file paths can be opened or copied, list output includes dump command suggestions, and captured errors include copy buttons for full messages and stack traces.

### MCP server

When the Minecraft client starts, Viewfinder automatically starts a Streamable HTTP MCP server at:

```text
http://127.0.0.1:7150/mcp
```

The server exists only while Minecraft is running with Viewfinder loaded. Run `/viewfinder status` in-game to confirm the address. If port `7150` is already in use, Viewfinder reports the startup failure in the Minecraft log.

> [!WARNING]
> The MCP server has no authentication. It is restricted to IPv4 loopback and rejects non-loopback `Host` and `Origin` values, but any process on your computer may still attempt to connect. Do not expose it through port forwarding, a reverse proxy, an SSH tunnel, container networking, or a bind address such as `0.0.0.0`.

> [!CAUTION]
> Viewfinder is not read-only. An MCP client can edit unpacked shaderpack sources, change shader options, reload shaders, capture screenshots and GPU data, and control a singleplayer scene. Connect only clients and agents you trust, and review tool calls that mutate files or game state.

There are no legacy REST or OpenAPI endpoints. MCP clients must use Streamable HTTP.

#### Add to Codex using the plugin

The plugin is the recommended Codex setup because it installs both the MCP connection and Viewfinder-specific agent instructions. It does not install the Minecraft mod or start the server.

1. Install Viewfinder in Minecraft and launch the game.
2. Clone this repository and open a terminal in its root directory.
3. Add the repository's local plugin marketplace and install Viewfinder:

   ```bash
   codex plugin marketplace add .
   codex plugin add viewfinder@viewfinder-local
   ```

4. Start a new Codex session so it loads the plugin and bundled MCP configuration.
5. Open `/plugins` in Codex to confirm that `viewfinder` is enabled, then ask Codex to get the Viewfinder diagnostics.

During plugin development, reinstall the plugin after changing its manifest so Codex refreshes its cached copy.

#### Add to Codex without the plugin

To register only the MCP server, without Viewfinder's plugin instructions, run:

```bash
codex mcp add viewfinder --url http://127.0.0.1:7150/mcp
```

Start a new Codex session after adding it. You can inspect or remove the registration with:

```bash
codex mcp list
codex mcp remove viewfinder
```

#### Add to another MCP client

Configure a Streamable HTTP server named `viewfinder` with the URL `http://127.0.0.1:7150/mcp`. Clients that use an `mcpServers` JSON object commonly accept:

```json
{
  "mcpServers": {
    "viewfinder": {
      "type": "http",
      "url": "http://127.0.0.1:7150/mcp"
    }
  }
}
```

The exact settings filename and schema depend on the client. Restart the MCP client after changing its configuration, and keep Minecraft running while using Viewfinder.

Tools:

| Tool | Description |
|---|---|
| `get_mcp_status` | Inspect the active request and bounded shared queue without waiting behind it |
| `run_actions` | Preflight and execute up to 64 ordered actions as one non-interleavable queued job |
| `get_diagnostics`, `clear_diagnostics`, `reload_shaders` | Read or reset diagnostics and reload Iris |
| `list_shaderpacks`, `switch_shaderpack` | Discover installed packs and switch pack/config |
| `set_shader_options`, `write_shader_source`, `write_shader_sources` | Mutate config/source and reload once; use the plural tool for multiple files |
| `inspect_program`, `dump_program_binary` | Reflect or export by preferred exact Iris name, or explicit GL id when unnamed |
| `inspect_texture`, `dump_texture` | Probe or export by preferred Iris name, or explicit GL id when unnamed |
| `inspect_ssbo`, `dump_ssbo` | Probe or export an explicit Iris SSBO binding |
| `capture_frame`, `capture_pass_outputs` | Capture a frame or selected pass attachments |
| `profile_frames` | Profile up to 1–600 frames with summaries by default and optional raw samples |
| `set_scene`, `control_ticks` | Control an integrated singleplayer server scene |

Every operation except `get_mcp_status` and reads of immutable capture files uses one global FIFO execution queue. One
active request and up to 32 waiting requests may share the running Minecraft instance; excess calls fail instead of
growing an unbounded queue. Successful queued results include a request ID, initial queue position, queue time, and
execution time. `get_mcp_status` deliberately bypasses the queue so another agent can inspect an active or waiting job.

Use `run_actions` whenever later steps depend on state established by earlier steps. Every nested action is validated
before action zero executes, preventing argument mistakes from causing partial mutations. Each action has a `type`
equal to an existing tool name and otherwise uses that tool's arguments. The action-only `wait_frames`, `list_programs`,
`list_textures`, and `list_ssbos` types provide bounded render warmup and lightweight resource discovery. Reload-capable tools return only
their complete fresh errors and parsed `compilerMessages` when present, so inspect that response directly instead of
appending `reload_shaders` or `get_diagnostics`. Use `get_diagnostics` only when the broader runtime snapshot is needed;
its metrics are summarized unless `includeSamples` is true.

Program and texture tools require exactly one selector. Prefer exact Iris `name` values because they survive reloads;
use `id` only for unnamed OpenGL objects. SSBO tools require an explicit `index`. Missing identifiers never default to
zero, and lookup failures include current valid names, indices, values, or observed passes where available.

Use `write_shader_sources` for multi-file edits. It validates every path before writing, writes all requested sources,
and performs at most one reload. This avoids repeated shader compilation and returns any resulting errors in the same response.

For deterministic scene setup, freeze server ticks before `set_scene` in the same job and resume them when finished:

```json
{
  "actions": [
    {
      "type": "switch_shaderpack",
      "name": "MyShaderpack.zip",
      "resetConfig": true,
      "config": { "SHADOW_QUALITY": "HIGH" }
    },
    { "type": "control_ticks", "action": "freeze" },
    { "type": "set_scene", "time": 6000, "weather": "clear" },
    { "type": "wait_frames", "frames": 32 },
    { "type": "capture_frame", "frames": 0 },
    { "type": "profile_frames", "frames": 120, "maxSeconds": 60 },
    { "type": "control_ticks", "action": "resume" }
  ]
}
```

No other MCP tool or mutable-resource read can run between those actions. This is isolation, not rollback: if an action
fails, later actions are skipped and earlier Minecraft or file mutations remain applied. Manual in-game actions are
outside the MCP queue. If a job fails after freezing ticks, resume them with `control_ticks` when appropriate.

The bounded job and ordered-action model is adapted from [Vibris capture control](https://github.com/Luna5ama/vibris/blob/main/docs/capture-control.md#mcp-tools), scaled to Viewfinder's single in-process Minecraft runtime.

`dump_program_binary` is restricted to NVIDIA's proprietary OpenGL driver. Unsupported vendors are rejected before capture. Up to 16 MiB of the complete NVIDIA pseudo-assembly is returned inline as MCP text content and also retained at the returned capture URI/path; the result reports whether inlining occurred.

Resources:

- `viewfinder://shaderpack/manifest`
- `viewfinder://pipeline`
- `viewfinder://shaderpack/source/{path}`
- `viewfinder://patched-shader/{name}`
- `viewfinder://capture/{captureId}/{file}`

Capture tools write to `viewfinder_captures/<captureId>/` in the game directory. Viewfinder retains the newest 20 capture directories that it owns. Shader source writes are confined to `shaders/` and work only for unpacked directory shaderpacks. Scene and tick mutation is rejected outside singleplayer.
Capture resource reads are limited to 16 MiB; larger captures remain available at the filesystem path returned by the dump tool.
