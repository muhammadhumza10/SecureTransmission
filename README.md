# Secure Transmission

Underwater camera relay for a submarine project: streams the phone's camera
to a browser dashboard on your laptop over the network.

## A note on the notification

While streaming, the phone shows a persistent notification: **"Secure
Transmission is running."** This is set low-priority and silent (no sound, no
vibration, no badge) so it won't buzz or spam you — but it cannot be hidden.
Android requires a visible notification for the entire time an app is using
the camera in the background; that's enforced by the OS itself (not by this
app) as protection against covert camera use, and there's no setting that
turns it off. It doubles as a handy "yep, still streaming" check during a dive.

## 1. Build the APK (no Android Studio needed)

1. Push this folder to a new GitHub repo.
2. Go to the repo's **Actions** tab → you'll see the "Build APK" workflow run
   automatically on push (or trigger it manually via "Run workflow").
3. When it finishes, open the run → under **Artifacts**, download
   `secure-transmission-debug-apk`. Unzip it to get `app-debug.apk`.
4. Transfer `app-debug.apk` to your phone (email, cloud drive, USB) and install
   it. You'll need to allow "install from unknown sources" since it's not from
   the Play Store.

## 2. Set up networking (phone on mobile data, laptop on WiFi)

These are different networks, so the laptop can't reach the phone directly —
you need both devices on one virtual network. Easiest free option:

1. Install **Tailscale** on your phone (Play Store) and on your laptop
   (tailscale.com/download).
2. Sign in with the same account on both.
3. Open the Tailscale app on your phone — it shows an IP like `100.x.x.x`.
   That's the address your laptop will connect to, and it stays reachable
   whether the phone is on mobile data or WiFi.

(If you ever test with both devices on the *same* WiFi, you can skip Tailscale
and just use the phone's regular WiFi IP shown in the Secure Transmission app.)

## 3. Run it

1. On the phone: open **Secure Transmission** → tap **Start Streaming** →
   grant the camera permission. The screen shows the device's IP and a
   stream URL like `http://100.x.x.x:8080/stream`. Mount the phone in your
   submarine housing.
2. On the laptop: open `dashboard/dashboard.html` directly in any browser
   (double-click it — no server or install required).
3. Enter the phone's IP (the Tailscale `100.x.x.x` address) and port `8080`,
   click **Connect**. The live feed appears. Use **Save Snapshot** to grab a
   still frame, or **Fullscreen** for a bigger view.

## Project structure

```
secure-transmission/
├── app/                        Android app (Kotlin, CameraX, NanoHTTPD)
│   └── src/main/java/com/humza/securetransmission/
│       ├── MainActivity.kt         start/stop UI, shows device IP
│       ├── CameraStreamService.kt  foreground service, camera capture, notification
│       └── MjpegServer.kt          embedded HTTP server, serves MJPEG stream
├── .github/workflows/build-apk.yml GitHub Actions: builds the APK for you
└── dashboard/dashboard.html         Laptop viewer — just open in a browser
```

## Notes on your hardware

- Your screen issue on the phone doesn't matter much here — once streaming
  starts you don't need to look at the phone; the laptop dashboard is the
  display.
- Check your phone's actual IP68 rating conditions before the dive — most
  ratings are tested in still water, not with a housing/mount under pressure
  against the seals. Consider a waterproof case as backup even within the
  rated depth.
- The stream resolution/quality is controlled by the camera's analysis
  output size and JPEG quality (set to 80 in `CameraStreamService.kt`) — lower
  the JPEG quality value there if mobile data upload is choppy underwater.
