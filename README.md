# androidloader

Sideload iOS apps from an Android phone, the way [iloader](https://github.com/nab138/iloader)
does from a PC. Plug the iPhone into the phone's USB-OTG port, pair, install — no
computer in the loop.

The app speaks Apple's `usbmuxd` protocol directly over the socket that
`termux-usbmuxd` exposes, then layers the usual lockdownd / AFC /
`installation_proxy` services on top. Everything above usbmuxd is plain protocol
work and is implemented here in Kotlin.

## Requirements

- An Android phone with USB-OTG support, Android 8.0+
- [Termux](https://f-droid.org/packages/com.termux/) and
  [Termux:API](https://f-droid.org/packages/com.termux.api/), both from F-Droid
- An iPhone you own, on iOS 16+, with Developer Mode enabled

## Setup

In Termux:

```sh
pkg install usbmuxd libimobiledevice termux-api
```

If you want the app to start the daemon for you, also allow external commands.
Termux checks this before running anything another app asks for and reports the
refusal only in a notification, so skipping it looks like the app silently doing
nothing:

```sh
mkdir -p ~/.termux
echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

Then force-close Termux from the app drawer so it re-reads the setting.

**This step is optional.** The app talks to a usbmuxd over loopback regardless of
who started it, so if you would rather run the command by hand, the *Start
usbmuxd in Termux* button is unnecessary and *Look for iPhone* is enough.

## How the USB bridge works

`usbmuxd` cannot open the `usbfs` device nodes from inside an Android app's
sandbox — those nodes belong to a system group an app UID is not in. Termux:API's
`termux-usb` can, because it claims the device through Android's USB API; with
`-E` it executes a child process that inherits the claimed file descriptor. That
is the whole trick, and it needs no root:

```sh
termux-usb -r -E -e "usbmuxd --socket 127.0.0.1:27015 --pidfile NONE -f" /dev/bus/usb/001/002
```

**The socket address matters.** The daemon's default is a Unix socket inside
Termux's private data directory, which an APK cannot open — different UID, `0700`
permissions. Loopback TCP is shared between apps, so TCP is the only transport
reachable from the app.

Find the device path with `termux-usb -l`. If no device appears, check that the
cable carries data and that the phone accepts OTG; some vendor kernels still
refuse, and no wrapper can force access the system will not grant.

## How pairing actually works

The trust prompt is raised by the `Pair` **request**, not by the TLS handshake. An
earlier version of this app started a TLS handshake before sending anything, so
the prompt never appeared and the failure looked like a certificate problem.

There is no `StartPairing` step, and no TLS before `Pair`. The sequence is:

1. Connect to lockdownd on port 62078, in plaintext.
2. `GetValue` for `DevicePublicKey`.
3. Generate locally: an RSA-2048 root CA and a host certificate, **PEM** encoded.
4. Send `Pair` in **plaintext**, with a `PairRecord` dictionary containing the
   public parts only — the private keys are stripped, since they never leave the
   device.
5. The iPhone shows its trust prompt and returns the full record, including the
   device certificate and the host's own keys.
6. `StartSession` with the `HostID`. TLS is enabled afterwards, for later
   requests.

Three details are dictated by lockdownd rather than chosen, and each fails as an
unexplained "pairing failed" when wrong:

- **RSA 2048**, not EC.
- **PEM**, not DER. The record fields are PEM text, so DER fails to parse as a
  certificate at all.
- A **`HostID`**, which is what `StartSession` is keyed on. It is persisted per
  install, because generating a fresh one each time leaves entries in the
  device's trust list the user cannot see or remove.

The root is `CA:TRUE` with no key usage; the host leaf is `CA:FALSE` with
`digitalSignature, keyEncipherment`. Both use serial 0 and ten-year validity, as
libimobiledevice does.

## Using the app

1. Start usbmuxd — either tap **Start usbmuxd in Termux**, or run the command
   above in Termux yourself.
2. Plug the iPhone into the OTG port and unlock it.
3. Tap **Look for iPhone**.
4. Tap **Pair with this iPhone** and accept the trust prompt on the phone.

Pairing is cached per device, so later launches only need a session.

## How the descriptor actually gets to usbmuxd

This is the part that is genuinely not obvious, and getting it wrong produces
errors that point somewhere else entirely.

`termux-usb` requires a device path as a positional argument. With none it exits
immediately with `missing -l or device path` — that is a `termux-usb` usage error,
not a daemon failure. The app therefore finds the device itself through
`UsbManager`, because `UsbDevice.getDeviceName()` already returns the usbfs path
(`/dev/bus/usb/001/002`) that `termux-usb` wants.

The flags are not what they look like:

- `-e command` does **not** run a shell command. It exports the command as
  `TERMUX_CALLBACK`.
- `-E` sets `TERMUX_EXPORT_FD`, and the command then runs with the claimed
  descriptor in its environment as `TERMUX_USB_FD`.
- Termux's **libusb is patched** to read `TERMUX_USB_FD` and to use that single
  descriptor, skipping the `/dev/bus/usb` scan entirely. Unpatched libusb looks
  for device nodes an app UID cannot open, which is the entire reason a plain
  `usbmuxd` cannot start.

Two further traps in the run-command intent, both of which report themselves as
something else:

- The service is `com.termux.app.RunCommandService`, in the **Termux app**.
  Termux:API removed its own copy, so aiming at the old name raises
  `ActivityNotFoundException` — indistinguishable from "Termux is not installed".
- It is a **Service**, so it must be started with `startService`. `startActivity`
  produces the same misleading error.

And Termux refuses commands from other apps unless `allow-external-apps` is set,
reporting the refusal only as a notification.

## What is implemented

| Layer | State |
| --- | --- |
| usbmuxd wire protocol, framing, device list | complete, unit tested |
| Service framing (length-prefixed messages) | complete, unit tested |
| Property lists: XML encode/decode, binary decode | complete, unit tested |
| lockdownd session and pairing, over plaintext as lockdownd expects | complete |
| Pairing record storage, host identity generation, PEM encoding | complete |
| Termux:API launcher, package visibility | complete |
| AFC file transfer | written, untested against hardware |
| `installation_proxy` install and uninstall | written, untested against hardware |
| IPA inspection | written, untested against real bundles |
| Apple ID sign-in (SRP-6a, GrandSlam) | crypto complete and unit tested; the HTTP flow and the private developer endpoints are **not** done |
| Codesigning, provisioning profiles | not started |

Read the table as the honest state of the project. Everything above the AFC row is
exercised by unit tests. Nothing below it has been run against an iPhone, and the
GrandSlam work stops at the cryptography.

## Free developer account limits

A free Apple ID provisions for **7 days** and allows **3 apps per 7 days**. The
third app of a rolling week will fail with `-402620395`; the message the app shows
names this. Development certificates and app IDs are also capped, and expired
profiles need removing from the device before a fresh one can be used.

## Building

```sh
./gradlew assembleDebug
```

Requires JDK 17 and an Android SDK with platform 35. Outputs
`app/build/outputs/apk/debug/app-debug.apk`.

CI builds both variants on every push and pull request. A signed release needs
four repository secrets — `KEYSTORE_BASE64` (the keystore, base64-encoded),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them the release APK is
built unsigned and no release is published, so forks run the full workflow.

## Design notes

Two details in this protocol are easy to get wrong and are worth knowing before
changing the code:

- **`PortNumber` is byte-swapped** before it is sent. The field is read back as a
  little-endian 16-bit value, so lockdownd's 62078 (`0xF27E`) goes out as
  `0x7EF2`. The reference client does this with `port.to_be()`, which looks like
  a no-op until you know the field is little-endian on the far side.
- **SRP-6a here follows the `srp` crate, not RFC 5054.** The identity hash is
  `H(username | ":" | password)`, and `M1`/`M2` are `H(A|B|K)` and `H(A|M1|K)`
  with no `H(N) XOR H(g)` term. `k` hashes `N | PAD(g)`, so `g` is zero-padded to
  the modulus width. Any of these differing from the RFC still produces a
  self-consistent client that fails exactly like a wrong password.

## License

MIT. Not affiliated with Apple, iloader, or Termux.
