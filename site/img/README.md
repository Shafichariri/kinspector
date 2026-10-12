# Screenshots for the landing page

All of these are real renders, regenerated rather than hand-edited. Retake them after a visible UI
change.

| Files | What | How |
|---|---|---|
| `overlay-*.png` | The in-app overlay at 360dp | `./gradlew :inspector-ui:jvmTest --tests '*ListRenderTest*'`, then copy from `inspector-ui/build/screenshots/` |
| `web-{list,path,timeline,waterfall}-{dark,light}.png` | The web UI against the desktop sample's scripted session, `GET /slow` selected (on `path`, its Cart visit opened from the map) | Scratch daemon on its own port and `--data` dir, sample pointed at it, `-Dinspector.sample.autofire=true`, then headless Chrome at 1440×860 @2x, resized to 2000px wide |

**Never use your everyday daemon or archive for these.** It holds real app traffic, and redaction
is off by default. This repository is public, so no real app's hosts, paths, tokens or names may
appear in any image.

Still wanted: the overlay on a real device frame (emulator / simulator / iPhone), an MCP session in
an editor, and a 10–15 s clip of pill → call → same call in the browser.
