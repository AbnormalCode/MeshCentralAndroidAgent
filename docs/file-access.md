# File Access on Android

This document describes what the MeshCentral file manager can reach on an
Android device, and how the device user grants that access.

## Summary

Android's scoped storage stops an ordinary application from reading the whole
file system, so the agent exposes two different kinds of root:

| Root | Backed by | Available |
| --- | --- | --- |
| `Files` | A folder the user picked with the Android document picker | After the user grants it |
| `Images`, `Audio`, `Videos` | MediaStore collections | Always |
| `Sdcard` | Direct external-storage paths | Android 9 and earlier |

`Sdcard` is a legacy root. On Android 10 and later, scoped storage means an
application without the restricted all-files permission cannot enumerate
external storage directly, so that root lists little or nothing on a current
device. `Files` is the replacement, and it is the only root that supports the
full set of operations everywhere.

## Granting a Folder

The device user grants a folder from the agent's overflow menu:

1. Choose **Grant File Access**.
2. Pick a folder in the Android document picker. The whole of internal storage
   or an SD card can be chosen, as can a single folder.
3. The grant is persisted, so it survives restarts and reboots.

**Clear File Access** in the same menu withdraws the grant. The `Files` root
then disappears from the MeshCentral file manager. Uninstalling the app also
releases it.

## Supported Operations

| Operation | `Files` | `Images`, `Audio`, `Videos` | `Sdcard` |
| --- | --- | --- | --- |
| List a folder | Yes | Yes, flat | Android 9 and earlier |
| Download | Yes | Yes | Android 9 and earlier |
| Upload | Yes, any file type | Recognised media types only | Android 9 and earlier |
| Delete | Yes | Yes, may need device confirmation | Android 9 and earlier |
| Create folder | Yes | No | Android 9 and earlier |
| Rename | Yes | No | Android 9 and earlier |

MediaStore roots are flat collections rather than folder trees, which is why
they have no folder or rename support. An upload to a MediaStore root has to
have a recognised media extension, because Android needs a MIME type and a
collection to file it under.

## Path Handling

Requests below `Files` are resolved by `StorageAccess.kt`. A virtual path such
as `Files/Reports/2026` is walked one segment at a time, each looked up by
display name among the children of the previous folder, starting from the
granted folder. Segments are rejected unless they pass the same
`isSafeFileName` check used elsewhere in the agent, so `..`, path separators and
embedded null bytes cannot be used to escape the granted folder. The `Sdcard`
root keeps its existing canonical-path containment check.

An upload that would overwrite an existing file deletes the old document first,
so the file is replaced rather than Android creating a second copy named
`file (1)`.

## Limitations

- The agent can only see what the user granted. Nothing outside that folder, and
  nothing belonging to another application's private storage, is reachable.
- Android may refuse a grant for certain directories, such as the root of
  internal storage on some versions, and the `Download` folder on Android 11 and
  later. The picker states this at the time.
- File modification times come from the document provider. Providers that do not
  report a size or timestamp show zero.
- Deleting from a MediaStore root can require the device user to confirm, which
  raises Android's own dialog on the device.
