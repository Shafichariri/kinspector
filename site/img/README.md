# Screenshots for the landing page

Each card in `site/index.html` has a drawn stand-in and a comment naming the file that replaces it.
Drop the PNG here, swap the mock `<div>` for an `<img>`, keep the `alt` text descriptive.

| File | What to capture | Source |
|---|---|---|
| `overlay.png` | List with a marker, a signal row and a 4xx | `:sample:android` on an emulator, or `inspector-ui/build/screenshots/` from `ListRenderTest` |
| `overlay-ios.png` | Same, on the iOS simulator (shows the Dynamic Island insets) | `sample/ios` |
| `web-ui.png` | A row selected with the JSON tree open | Real browser against the sample session |
| `mcp.png` | An agent answering a question with 2–3 tool calls | Your editor, against the sample session |
| `daemon.png` | `inspector serve` + the archive folder | Terminal |
| `demo.mp4` | 10–15 s: tap pill → open call → same call in the browser | Screen recording |

**Only sample-app traffic.** This repository is public: no real app's hosts, paths, tokens or
names may appear in any image. Redaction is off by default, so check headers before you capture.
