# Shared-space drive and product UI: implementation contract

Status: design approved for implementation; new runtime acceptance is pending.
Baseline: `b0828910fa6ff4057e7a0825f8c3508e17b255c1` (Preview 2).

## User-visible model

- A **Shared Space** is a persistent, app-private directory exposed as a genuine
  RDPDR filesystem device. It is not a renamed clipboard transfer or a snapshot
  of the whole phone. Remote Explorer and the app browser operate on the same
  workspace through one broker.
- Sharing is off until explicitly enabled. Read-only is the default; read/write
  consent explains that the remote computer can modify, rename and delete files
  within this space. Nothing outside the space is exposed.
- Android's system file picker explicitly imports or exports files. There is no
  automatic synchronization with the original selected Android document. Normal
  session closure, revocation and process restart do not purge the workspace.
  Already-authorized delete-on-close operations can complete when handles close;
  stopping sharing does not undo accepted writes or deletions.
- App list/import/export actions remain usable during a remote session through
  the same broker and sharing rules. An active conflicting writer can produce a
  visible busy error, never an unsafe bypass or a forced desktop restart.
- Unsupported peers (including the known GNOME 46 filesystem-device limitation)
  must not be presented as a mounted drive. Existing CLIPRDR transfer remains a
  separate fallback. Device announcement alone is not server acceptance.
- Generic live read/write SAF trees are deliberately outside this iteration:
  provider rename, deletion and name-allocation semantics cannot be advertised
  as Windows filesystem atomicity. Optional read-only external trees are also
  deferred until the primary workspace is complete.

## Isolation and lifetime

- The stock FreeRDP drive filesystem plugin stays disabled. A custom device uses
  the pinned public DEVICE/IRP interfaces; no arbitrary filesystem path is
  accepted from the server or UI.
- One typed native workspace broker owns root-relative, no-follow filesystem
  operations, share modes, local export leases, remote handles, quotas and
  exclusive import commits. Kotlin stages imports outside the exposed root.
- Reject traversal, alternate streams, reparse/symlink/hardlink escapes, invalid
  or ambiguous names, nonregular files, oversized paths/requests and stale
  generations. Directory deletion is empty-directory-only; overwrite behavior
  follows the requested disposition, not an implicit convenience fallback.
- User selections remain bound to the chosen object while a system picker or
  confirmation is open. Broker-owned leases/identity checks must reject a
  concurrently replaced file or directory atomically; resolving the same path
  again in the UI is not sufficient. No SAF/provider I/O holds the broker lock.
- Revocation closes future authority and queued work without purging the workspace.
  It does not undo accepted writes/deletions; explicit delete-on-close requests
  can complete as the corresponding handles close.
  Session teardown closes all remaining handles. Ordinary storage exhaustion or
  a busy file must not disconnect the desktop.
- Disk budgets and limits must be visible and predictable. Exact values and
  implemented IRP classes are recorded with the implementation, not inferred
  from this design document.

## Flow control

The approved direction is a small, exact-source-hash-pinned, read-only FreeRDP
accessor for pending buffered transport output under its existing write lock.
It must validate the supported direct-TLS/default-write BIO chain and distinguish
unsupported/closed/error from an empty buffer. It does not modify TLS policy,
certificate trust, authentication or the transport write implementation.

RDPDR admission, pending requests and completions are bounded. Only the native
owner loop completes an admitted response and immediately pumps the channel
manager; output credit is based on actual buffered bytes, never bytes accepted
by an asynchronous channel callback. The bound is the drive's incremental
contribution, not a claim about total process RSS or all RDP channels.

Release requires real stalled-peer plateau/resume/hash/cancel evidence and
source-pin/unsupported-stack negative tests. There is no unverified claim that
the previous clipboard lifetime quota can simply be removed.

## Product UI boundary

Use coherent tokens, navigation, forms, connection cards, loading/empty/error
states, English/Chinese resources, light/dark themes and accessible controls.
Keep the established default direct touch and immersive one-handle session UI;
do not disturb the desktop View/InputConnection identity, IME crop/scale,
safe-area calculations, keyboard composition or live-session rotation.

The drive screen shows scope, access mode, capacity, actual connection state,
import/export progress, busy/unsupported/revoked errors and deliberate cleanup.
No silent data migration, profile replacement or credential reset is part of
visual polish.

## Finite acceptance

1. Existing JVM/native/security/clipboard/fullscreen/IME acceptance stays intact.
2. Actual xrdp RDPDR mount: read-only denial and writable list/read/create/write/
   mkdir/rename/delete, independent byte hashes, same live files from the app,
   conflict handling, quota, revocation and reconnect persistence.
3. Real Android Binder/SAF import/export and adversarial provider/FD/path tests.
4. Actual GNOME non-acceptance/unsupported classification with clipboard fallback;
   silence is not inferred to be a rejection or explicit missing capability.
5. A bounded UI screenshot/usability matrix: light/dark, English/Chinese,
   narrow large-font and expanded layouts, controls and back/dismiss behavior.
6. Exact final main APK signature, native closure/JNI, 16 KiB alignment, notices,
   source/tag/asset binding and public-download verification.

No physical-device, Windows-server end-to-end, universal filesystem, unlimited
throughput or Windows App performance-equivalence claim follows from these
fixtures. The user declined persistent signing-key setup for this iteration.
Preview 3 therefore uses a distinct `com.rdp.sync.preview3` application ID and
development signing, alongside Preview 2. No uninstall, automatic profile/data
migration, durable signing credential or existing-release replacement is part of
this delivery. Future builds with a different development signer are not promised
to update this package in place.
