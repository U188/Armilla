# Browser history and DOM regression notes

## Reproduced on the installed browser (not the modified build)

- Created a dedicated temporary tab; preserved the user's Wikipedia tab.
- Opened example.com and clicked its IANA link with the browser tool.
- Native go_back landed on about:blank. history.length was 3.
- Native go_forward returned to IANA.
- JavaScript history.go(-1), followed by a separate location read, returned to example.com.
- Thus the Example entry still exists; this is not evidence of a lost history stack.
- Closed the temporary tab after checking DuckDuckGo.

## Implementation evidence and local changes

Chromium's AwContents.goBack/goForward call NavigationController.goBack/goForward,
whose native implementation uses GetIndexForGoBack/Forward (skipping entries).
AwContents.goBackOrForward calls goToOffset, which uses GetIndexForOffset instead.
Primary sources inspected: android_webview/java/src/org/chromium/android_webview/AwContents.java
and content/browser/renderer_host/navigation_controller_impl.cc on chromium.googlesource.com.
This supports the history-intervention explanation for automation clicks without user activation.

- UI and agent actions now request native goBackOrForward(-1/+1).
- Availability uses canGoBackOrForward(-1/+1), consistent with these semantics.
- Agent traversal waits for the target native index and page completion, not a 300 ms sleep.
- Timeout and absent history entries return failure, not navigation success.
- Page information and history results include native history index/size/availability.
- Unit coverage added for one-step index arithmetic, boundaries and textarea script generation.
  These tests do NOT simulate Chromium history intervention or prove on-device success.

## Selector observations

- User reported #toc li returned 0 on Wikipedia. On subsequent inspection of the
  existing List of HTTP status codes tab it matched 25 nodes; dt matched 105.
- dt samples were 100 Continue, 101 Switching Protocols, 102 Processing. These are
  definition-list/status entries, not necessarily table-of-contents entries.
- DuckDuckGo homepage matched one textarea[name="q"] and no input[name="q"].
- These are page observations, not permanent site contracts. Discover current DOM first.
- Existing type implementation already supports textarea's native value setter and
  requestSubmit. Added a regression test; no site-specific selector substitution.
- Empty collection results now suggest DOM inspection; keyword-filtered emptiness is
  distinguished from no collected text. No website-specific selectors are hardcoded.

## Verification status

Local source and whitespace checks only. Changes have not been pushed, cloud-built,
installed or tested with the new native traversal on a device. The old APK is unchanged.
Real Chromium regression remains required after cloud build: example -> IANA -> back
must return example; forward must return IANA. Also test duplicate URLs, fragment
history, boundaries, slow loads and both browser toolbar and agent actions.
