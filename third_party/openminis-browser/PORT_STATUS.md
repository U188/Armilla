# Local browser port status

## Integrated at source level

- AgentAppRoot's existing AgentBrowserScreen now renders the imported BrowserSheet,
  embedded inside the existing app navigation (no second whole-screen bottom sheet).
- Preserved tab bar (up to 3 tabs), new/close/select tab, address input, navigation,
  history/search, download list/progress/cancel/open, UA/custom UA, viewport presets,
  cookie/data controls, and idle-tab timeout settings.
- Material3 UI is bridged to Eta's active Miuix palette/darkness. Browser-only string
  resources are namespaced, with English, Simplified and Traditional Chinese copies.
- AgentBrowserSession now owns the same tab pool as the UI and keeps the original
  execute/snapshot/preview/cancellation interfaces for existing call sites.
- UI acquisition cancels and waits for the active tool; visible UI blocks new tools.
  UI release restores off-screen bounds, with ownership identity to avoid rapid
  close/reopen races.
- browser_use adds new_tab/close_tab/list_tabs/tab_id; old actions, paging, coordinate
  inputs, submit, timeout aliases and read_image=false are retained.
- Eta guest paths, cookie env-file offload, output limits, bounded screenshot allocation,
  download limits and a narrow cache FileProvider path replace upstream-specific wiring.
- Removed implicit Google-login app launches; no Antigravity model/login code imported.
- Prior Antigravity removal work remains in the working tree.

## Checks actually performed

- Parsed the three locale XML sets; no duplicate string resource names.
- Resolved all 54 imported UI string names against default resources.
- Compared all 26 browser tool actions with the ported action enum.
- Checked removed old-browser method call sites and stale upstream sandbox imports.
- git diff --check passes.
- Added six compatibility regression tests, NOT executed yet.

## Not verified yet

- No Kotlin/Compose compilation, unit test run, APK, installation or visual screenshot.
- Need device tests: mount/unmount/rotation, rapid close/reopen, tab close and renderer
  death, keyboard, dark theme, downloads and redirects, cancellation, local-file pages,
  cookies, per-tab tool targeting and long-running async page scripts.
- Android per-domain cookie deletion is best-effort for root-path cookies; only the
  confirmed 'clear all' action clears the full shared store/site data.
- This is an integrated source draft, NOT a claim of production-ready full equivalence.

## Build/publication authorization

Working branch: private/openminis-browser (historical name).
The repository owner explicitly authorized public push and GitHub Actions compilation
after being informed the origin repository is public. Preserve upstream attribution;
this authorization is not a declaration of license compatibility. Do not create a
Release or install automatically. See subsequent CI results for actual validation.
