# Android control and foldable screen sharing (preview)

## Setup

1. Install a build from this fork and pair with your MeshCentral server.
2. In the app Settings, turn on **Allow remote control while sharing** and accept
   the disclosure. Control is off by default.
3. Use **Open Accessibility settings** and manually enable the MeshCentral service.
   Samsung normally lists it under Accessibility > Installed apps. Sideloaded APKs
   may require Android's restricted-settings approval before enabling the service.
4. Open remote desktop from MeshCentral and approve Android's full-screen sharing
   request on the phone. Keep the phone unlocked.
5. Stop sharing from Android/the notification or turn off remote control to end
   input. Accessibility approval alone does not enable input without active sharing.

Android 6 supports viewing; gesture control requires Android 7 or newer. Existing
installations need an APK signed with the same signing key and a higher versionCode.
A CI debug APK cannot upgrade a Google Play-signed installation. Production signing
uses the existing release workflow and keystore. This preview has not been verified
on a physical Galaxy Z Fold7.

## Initial controls

| Viewer input | Phone action |
| --- | --- |
| Left click | Tap |
| Left-button drag, then release | Swipe/drag along the recorded path |
| Mouse wheel | Vertical swipe at the pointer |
| Right click, Escape or browser Back | Back |
| Middle click or Home key | Home |
| Menu/application key | Recent apps |
| Printable keys/Unicode input | Edit supported focused text fields |
| Backspace/Delete and left/right arrows | Delete text or move the cursor |
| Tab | Attempt to focus the next control |
| Enter | Insert a newline into a supported text field |

The existing MeshCentral binary input protocol is used. No browser or server
protocol change is needed. Mouse gestures are submitted on release; they do not
provide a live held touch. Execution is limited to one second. Queued input is
bounded; excessive bursts can be dropped. Multi-touch/pinch, arbitrary hardware
keys, clipboard sync and input locking are not implemented. Some apps do not
expose Accessibility editing actions; password fields are not edited.

## Local authority and tunnel rights

Every action requires local opt-in, a connected Accessibility service, an active
full-display capture, an unlocked/interactive phone and a tunnel with operator
remote-control rights. Missing, view-only, limited-input and no-desktop permissions
deny input (except the server's full administrator rights, which match its normal
rights semantics). Explicit view-only mode always denies input.

The checks run again before queued actions execute. Capture size changes invalidate
coordinate snapshots; late frames cannot restore them. Screen-off and preference
changes clear per-tunnel input state. Disconnect and projection stop reject pending
actions. A gesture already accepted by Android may finish for up to one second.
The Accessibility tree and field contents are not transmitted to the server.

Android owns capture permission and the sharing notification remains visible.
This feature does not bypass keyguard, secure content, protected text fields,
work-profile restrictions or OEM policy. Consent cannot be enabled automatically.

## Capture across folding and rotation

The capture service listens for default-display size/configuration changes,
including folding without a rotation change. Android 14+ captured-content size
and visibility callbacks also adjust capture. It resizes the existing VirtualDisplay
and attaches a replacement ImageReader, respecting the one-create-per-projection-token
rule. If Samsung revokes projection on a physical display switch, the app stops
sharing and asks the user to start a new session rather than reusing the token.

Frames are cropped to remove row padding before scaling. Encoded dimensions,
advertised dimensions and input mapping share the same geometry. Size/scale changes,
refresh and new viewers trigger a full frame and reset old input mappings. App-sized
or hidden capture is view-only. External DeX/display selection is not included.

JPEG, PNG and WebP updates use changed 64x64 tiles or full frames. Slow outbound
queues cause frame skipping. The requested frame-rate setting is not yet applied
as a time-based capture limiter; full-resolution inner-screen capture can be expensive.

## Fold7 device checklist

JVM tests exercise protocol validation, coordinate mapping, gesture/key state,
Unicode, rights restrictions and stale-frame rejection. They do not prove Android
Accessibility behavior, capture consent or Samsung folding behavior.

- Verify viewing but no input with control off or Accessibility disabled.
- Enable both controls and share the cover screen. Test taps at all corners,
  a drag, wheel scrolling, Back/Home and typing in a non-sensitive field.
- Repeat at 50% scaling; type non-ASCII text and emoji, then Backspace an emoji.
- Unfold without rotation. Verify fresh dimensions/full frame and correct corner
  taps. Fold back, then repeat several times while rotating each screen.
- Begin a drag and fold before releasing. Verify old-coordinate input is discarded.
- Stop sharing, turn off control, disable Accessibility, disconnect or lock the
  phone. Verify further remote input is ignored.
- Connect a view-only user and a second viewer; verify no view-only input and that
  a new viewer gets a complete frame on a static screen.
- End capture using Android's controls, then approve a fresh session. Check recovery
  when Samsung revokes the projection during a physical display change.
