# Native Shared Space contract

This is a custom **RDPDR filesystem** device, not CLIPRDR presented as a disk.
Stock `CHANNEL_DRIVE` remains disabled. The only announced device is `RdpSync`;
the FreeRDP settings path is an opaque sentinel, never a filesystem path. The
client name is `Android`, not the phone hostname. The persistent private broker
is the same authority for remote operations and local explicit import/export.
It is not a live mirror of the Android documents chosen with the system picker.

## Supported scope

Sharing is selected before connecting: off, read-only (default), or explicitly
writable. Revocation immediately closes that generation's broker authority and
cancels prepared responses; bytes already passed to the transport cannot be
retracted. Re-enabling or upgrading requires an explicit reconnect. Nothing
silently reconnects, changes certificate policy or removes persistent files.

Implemented requests: create/open with the broker's explicit access, sharing,
disposition and option rules; typed close; positional read/write; EOF truncate;
flush; basic/standard/attribute-tag metadata; volume capacity/attributes;
single-entry directory enumeration; rename (including explicit replacement
subject to sharing rules); empty-directory/file delete-on-close. Only `*` and
`?` directory wildcard semantics are implemented (`*.*` means all). Literal
matching uses the same bounded NFC/uppercase key policy as the broker; `?`
consumes one Unicode scalar in that normalized key. This is not a claim of all
Windows/DOS wildcard or Unicode collation behavior.

Directory path checks use the broker's current canonical handle identity,
including local rename/move, not a second cached namespace. Invalid UTF-16,
embedded NUL, alternate streams, traversal, repeated or mixed separators, wildcard
parent components and ambiguous/reserved names fail closed. Homogeneous backslash
(Windows) or forward-slash (xrdp open) paths use the same confined relative
namespace: at most one device-root prefix is stripped before broker traversal;
it never becomes an OS absolute path. A single terminal wire NUL is accepted. Own JNI/wire conversions use one strict
scalar codec, avoiding modified UTF-8 and attached-thread JNI-local accumulation.

Pinned xrdp includes `WRITE_DAC` in its ordinary create access mask. In explicit
read/write mode only, the exact combined `0x0016019f`, `FILE_CREATE`, share-all,
zero-allocation request with the observed directory (`0x21`/DIRECTORY) or regular
file (`0x44`/NORMAL) options/attributes tolerates this ancillary bit without
granting it to the broker handle. Other DAC intents remain unsupported. Actual security query/set IRPs remain unsupported; no remote
ACL mutation, owner change, or privilege is enabled. Read-only mode still denies
this write intent, and other unsupported security-access bits still fail.

The stock xrdp zero-length `FileDispositionInformation` form means delete=true.
Only this payload-free form uses a separate wire-only broker operation: active
remote-owner RW handles require either existing DELETE access or the exact
metadata open (`0x00100080`, OPEN, options `0x20`, share-all). It revalidates the
current named inode under the broker mutex, writable state, non-root/empty-dir
rules and conflicting delete-sharing leases. No stored handle access is upgraded;
normal one-byte disposition and all local/JNI operations retain DELETE checks.
This is a documented legacy command shape, not detection or trust of a peer name.

Pinned xrdp 0.10.6.1 drops its mkdir request bookkeeping without closing the
returned directory FileId. For an exact metadata-open legacy **directory**
disposition only, the requesting CLOSE revalidates inode/owner/writable mode,
empty-directory status and share-delete leases, then removes that directory's
namespace entry even if the original mkdir handle remains. All retained IDs for
that removed inode become CLOSE-only tombstones: they cannot list, import,
create, mutate, or delete a same-named replacement. Accounting changes once.
Cancellation/ordinary dispositions clear this exception; ordinary broker/JNI
delete-on-last-close and regular-file behavior are unchanged. Revocation still
performs previously accepted delete-on-close cleanup, not new delete requests.

Unclosed server mkdir IDs (including removed-directory tombstones) still count
against the fixed **128-handle budget** until CLOSE or disconnect. The client
does not automatically close active, undeleted directories to conceal this
server limitation. This compatibility path applies only to explicitly deleted,
revalidated empty directories; it is not general Windows filesystem parity.

The pinned xrdp remove callback reports `STATUS_DIRECTORY_NOT_EMPTY` as Linux
`EBADF`. The synthetic nonempty-rmdir negative control accepts that errno only
for this operation and requires unchanged directory/child inode identities and
child SHA-256. The production status and general denial checks are unchanged.

Unsupported requests/classes/options return defined errors, not fake success:
security descriptors/ACLs, reparse points/links, notifications, locks, device
controls, unsupported attributes and settable birth/change times, and growing
allocation/preallocation without implemented reservation accounting. Creation/birth time is explicitly unknown (zero); access/write/change queries
report the actual stat timestamps, never copies of modified time. Unknown
create options are rejected by the broker. WRITE_THROUGH uses O_SYNC and
metadata fsync; unsupported no-buffering/oplock options are not silently
accepted. Change notifications are unsupported, so the remote file manager may
need Refresh to observe an app-side import/rename. No Windows-server parity claim has
been established by the xrdp fixture.

## Authority, quotas and local selections

Current broker bounds: 1 GiB logical workspace, 512 MiB per file, 4,096 entries,
16 path components, 4,096 UTF-8 path bytes, 255 UTF-8 component bytes (the shared
Java name policy additionally limits 240 UTF-16 units), 128 handles, 1 MiB per
read/write, and a 128 MiB filesystem free-space reserve. Actual available space
is the lesser of workspace quota and filesystem free space less that reserve.
Symlinks, hardlinks, nonregular files and unsafe private directory ownership are
not accepted. The workspace is not purged across session teardown; already accepted
delete-on-close requests can complete when their last handle closes.

App export, import destination, rename/delete and new-folder selections use
broker handles held across the picker/dialog, not a path re-resolved later.
Export's read lease denies concurrent writers/deletion. Import destination
leases deny rename/deletion, and commit validates the same still-live handle.
Provider I/O and staging occur outside the broker lock. Cancellation registers one exact batch token before provider staging; the
manager immediately rechecks its Java cancellation flag after registration to
close the pre-registration race. Only the matching active token can cancel
native copy work. A late old or unrelated newer cancellation cannot cancel
another batch, and finally ends only the matching batch. No UI cancellation
call takes the registration mutex or performs provider/file I/O. Staging openers retain
separate fixed ceilings: 512 MiB for Shared Space versus the unchanged 128 MiB
CLIPRDR file ceiling and unchanged finite CLIPRDR connection-response budget.

## Bounded queues and transport observation

- At most 32 reserved requests, 4 MiB aggregate input reservations and 8 MiB
  prepared-response reservations. A read reserves its maximum response before
  its asynchronously queued callback runs. Duplicate pending CompletionIds,
  unknown device IDs, malformed fragmentation and excess admission fail closed.
- Only the protocol owner completes an IRP, at most one per pump, when actual
  buffered ciphertext is at most 256 KiB. It immediately drains the public
  channel manager after completion. The next pump rechecks actual pending bytes.
  Prepared work wakes the owner; the fallback 100 ms event wait is not a fixed
  per-operation delay. Complete/Discard consumes an IRP exactly once, even on
  send failure; bookkeeping is retired before consumption.
- The incremental data-response contribution to that buffered TLS queue is the
  256 KiB gate plus one response (at most 1 MiB payload plus RDP/TLS framing).
  This is **not** a bound on total process RSS, kernel buffers, other channels,
  or proof that the peer application consumed data.
- Controls which upstream handles before the custom device are separately
  validated and limited to 64 KiB of lifetime ingress, never reset on announce.
  With one fixed device/name, pinned source emits at most 44 bytes for announce,
  60 bytes for capabilities plus at most a 36-byte device announcement, or a
  36-byte device announcement for other permitted controls. A conservative
  bound is 12 output payload bytes per admitted control byte (768 KiB lifetime,
  plus framing). This finite exception must be included in a whole-drive
  accounting discussion; it is not reusable drained credit. The pinned
  upstream client-name conversion can also retain small JNI locals on the
  attached channel worker across reannounces; the finite control budget and
  fixed short name bound this call count, and worker detach releases them.
  Android API 26+ has no pre-8.0 fixed local-reference cap ([official JNI
  guide](https://developer.android.com/ndk/guides/jni-tips#local-and-global-references));
  this is still not a total Java/native heap or process-RSS guarantee.
- An unsupported transport stack revokes access, not an invented empty queue;
  bounded error output has its own 1 MiB reserve. Normal disk quota/busy errors
  return NTSTATUS without disconnecting the desktop. Malformed/flooded channels
  or an exhausted abuse/control reserve may fail closed. There is no ordinary
  32 MiB drive read lifetime quota and no fake EOF on quota failure.
- Owner ingress increments an announce epoch before upstream can reinitialize.
  Pumping is barred until Device.Init processes that epoch and a new-epoch IRP
  callback proves upstream has left its private clearing phase. Init discards
  old reservations but preserves already-admitted future-epoch requests. Device
  rejection/free, concurrent completion and disconnect share once-only lifetime
  transitions. Stale upstream state rejection cannot replenish a leaked slot;
  an unprocessed reservation expires into fail-closed after 30 seconds.

The source accessor is appended by the hash-pinned patch described in
[third-party notices](../THIRD_PARTY_NOTICES.md). It holds the actual transport
WriteLock only for querying, validates direct TLS/default callbacks/front BIO/
SSL write BIO/BufferedSocket identity, and returns `-1` for not connected/closed
or `-2` for unsupported/error instead of treating either as zero. Owner context
lifetime remains separately required. Neither channel write completion nor
accepted-plaintext byte counters are used as socket-drain credit. The build
rejects unrelated dirty upstream sources and records per-ABI source, patch and
shared-library hashes; the official reference client is isolated and unpatched.

## Status v1 (13 signed 64-bit slots)

`[version=1, generation, mode, status, deviceId, unsignedReplyNtStatus,
readBytes, writtenBytes, pendingCount, pendingInputBytes, transportPending,
completedIRPs, peakTransportPending]`.

Status: `0` off; `1` requested/unconfirmed; `2` server filesystem capability;
`3` successful device announcement reply only; `4` successful device operation;
`-1` explicit capabilities omitted filesystem; `-2` rejected device reply;
`-3` locally revoked; `-4` unsupported transport; `-5` protocol failure.
No reply is not a rejection or proof of unsupported server behavior. Even state
3 is not proof of an Explorer-visible/mounted/readable drive. Generation-bound
revoke returns zero on success; stale generations cannot revoke a new session.

## Evidence boundary

`scripts/test-native.sh` retains the original eleven fixtures and adds broker,
production codec/UTF, isolated accessor and exact-batch JNI cancellation gates. The codec
suite links the production broker and pinned WinPR stream source. Its transport,
completion and channel dispatch are deterministic seams; the accessor guard
test compiles the exact production suffix with real OpenSSL BIO operations but
isolated FreeRDP object shapes. Neither test proves real socket backpressure.

Release acceptance still requires the actual Android/xrdp FUSE drive fixture,
real loopback-relay stalled pending-byte plateau and resumed independent file
hashes, input after resume, revocation, persistence, and unsupported GNOME
observations. A zero-window socket stalls all RDP traffic: no assertion is made
that input can reach the peer while that socket cannot drain. Results must be
recorded from those actual runs before this document's contract is described as
runtime-proven.

Bounded numeric `nativeGetDiag` fields additionally report current/peak reserved
response bytes, ready responses, remaining control ingress and error-output
reserves. They contain no file names, paths or content. The 13-slot state schema
is unchanged.
