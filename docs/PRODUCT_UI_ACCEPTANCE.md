# Product UI acceptance contract

This is a bounded acceptance plan, not a claim of flawless UI or a substitute for rendered evidence.

## Design vocabulary

- Local app UI: ink/teal, explicit foreground/background pairs for light and dark; semantic error surfaces with an icon and explanatory text, never color alone. Dynamic wallpaper colors remain an optional theme parameter, not the product default.
- System fonts with native Chinese shaping; 22sp major titles, 16sp body, 14sp supporting copy, flexible line height. User font scale is not capped.
- 24/16/8dp spacing; 24dp cards; 840dp maximum list width and 560dp form/detail width. Long identifiers wrap instead of disappearing behind ellipses.
- Actions have at least 48dp interactive targets. Long dialogs scroll; confirm/cancel stay outside scrolling content. Checkbox/radio/switch rows expose one selection action rather than duplicate nested controls.
- Main pages are safe-inset-aware. Session geometry is separate: the existing default fullscreen, single 48dp expandable handle, default direct touch, desktop/InputConnection identity, IME crop, rotation mapping and explicit bordered mode remain unchanged.

## Finite screen/state inventory

1. Connections: loading, first-use empty state, populated list, credential warning, sync busy/result.
2. Connection editor: new, edit, missing/locked credential, invalid required fields/port, save busy/error. Passwords never go into saved-instance state or evidence screenshots.
3. Connection details: long name/address/account, recovery action, explicit delete confirmation/cancel.
4. WebDAV: empty/configured, password hidden/revealed (empty synthetic value in evidence), test result, locked credentials, profile-only sync and explicit plain-text legacy upload warning.
5. Session: default fullscreen handle, expanded nonfocusable menu, display/network options, connecting/failed/disconnected, explicit reconnect, text entry, diagnostics.
6. Trust: first certificate and changed pinned certificate, old/new fingerprints, unchecked/checked approval, save pending/error; no implicit trust.
7. Clipboard files: unavailable, staged/not yet offered, offered/not yet confirmed saved remotely, receive confirmation, progress, export, cancelled/closed. Clearly labeled as clipboard files, **not** a redirected drive.
8. Shared space: persistent app-private workspace and genuine RDPDR consent/status, read-only default and separately acknowledged read/write scope, active/busy/unsupported/revoked/quota states, explicit SAF import/export, no automatic phone-folder synchronization.

## Evidence matrix and stopping conditions

`ProductUiInstrumentedTest` is UI-only synthetic evidence and does not establish network or RDPDR correctness. It captures English and Chinese in both light and dark, a 320dp constrained form at font scale 1.5, and a separately required >=600dp window at scale 1.5. For narrow **dialog** acceptance, run the emulator at an actual 320dp width; constraining a composable alone does not resize Android dialog windows. The expanded test explicitly skips on narrower windows. No physical foldable claim is made.

Inspect the actual PNGs in `product-ui-evidence`, not merely the test verdict. Require no overlap/clipping of essential text; accessible reachable primary/cancel actions; coherent hierarchy; long host/account/error text; legible foreground pairs; safe loading/disabled states. Verify dialog Back/dismiss and repeated actions. A test file existing is not a passed run.

`python scripts/test-product-ui.py` checks static bilingual parity, format placeholders and the explicit brand text-color pairs at WCAG 4.5:1. It is not a screenshot, TalkBack, contrast-over-remote-content or physical-device test.

The separate real-server and session geometry fixtures retain responsibility for certificate gating, fullscreen/IME/input ownership, stale picker invalidation, protocol acceptance, actual redirected-filesystem enumeration and byte integrity. Review those exact final-commit results separately before release. The isolated xrdp fixture exercises its RDPDR filesystem; it is not proof of the Windows Explorer GUI. No Windows Explorer UI test is claimed unless separately authorized and executed.

## Known boundaries to record with results

- Raw technical diagnostics may retain upstream protocol wording; they must never include credentials.
- No physical handset, radio, or hinge/fold posture is represented by emulator evidence.
- Final screenshots, checks run/skipped/blocked and remaining visual findings must be recorded before design sign-off. This is an after-only acceptance matrix, not a reconstructed before/after comparison; the existing Preview 2 release stays untouched.
