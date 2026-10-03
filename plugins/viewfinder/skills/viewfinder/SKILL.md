---
name: viewfinder
description: Debug, edit, profile, and capture live Iris shaderpacks through Viewfinder's MCP server in a running Minecraft client.
---

# Viewfinder

Use the `viewfinder` MCP server to work against the live Iris shaderpack. It
requires a running Minecraft client with Viewfinder loaded and cannot launch
Minecraft or compile GLSL standalone. If disconnected, continue useful local
work and request a client connection when live validation is needed.

## Workflow

- Use `run_actions` when a later operation depends on state established by an
  earlier one. It preflights all 1-64 actions and executes them in order without
  interleaving from other MCP requests.
- Treat a `run_actions` job as isolated, not transactional. A failed action
  skips later actions but does not undo earlier mutations, and manual in-game
  actions are outside the queue. Perform needed cleanup after a failure,
  especially `control_ticks` with `resume` after freezing ticks.
- Reload-capable tools return their complete fresh `errors` and parsed
  `compilerMessages`. Inspect that response directly; do not append
  `reload_shaders` or `get_diagnostics` only to verify it. Use
  `get_diagnostics` when the broader runtime snapshot is needed.
- Use `write_shader_sources` for multiple files so every path is validated
  before writing and the pack reloads at most once. Shader writes work only for
  unpacked directory shaderpacks and stay under `shaders/`.
- Warm the renderer with the action-only `wait_frames` before timing or capture
  when preceding actions reload the pack or change the scene. Use
  `profile_frames` summaries by default; request raw samples only when the
  analysis needs them.
- Use `get_render_settings` to query configured/effective render distance and
  base FOV, or `set_render_settings` with `renderDistance` (2-32 chunks) and/or
  `fov` (30-110 degrees). Changes persist in Minecraft options. Put a
  `wait_frames` action after changes before capturing or profiling the view.
- Prefer exact Iris program and texture names because they survive reloads; use
  a GL id only for an unnamed object. Supply exactly one selector. SSBO tools
  always require an explicit binding index. Use returned choices to recover
  from lookup or pass-name failures.
- `dump_program_binary` supports only NVIDIA's proprietary OpenGL driver. Its
  pseudo-assembly is inlined up to 16 MiB and remains available at the returned
  capture path when too large to inline.

All tools and mutable resource reads share one bounded FIFO queue except
`get_mcp_status`, which can inspect an active job without waiting. A full queue
returns a retryable error; use status to diagnose contention rather than
submitting overlapping dependent calls.
