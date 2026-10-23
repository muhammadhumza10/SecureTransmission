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

## 2. Deploy the free online relay (replaces Tailscale / IP addresses)

The phone and laptop never talk to each other directly. The phone pushes frames
to a small server on the internet; the dashboard (served by that same server)
receives them live. Frames are forwarded from memory and discarded - nothing is stored.

1. Push this repo to GitHub.
2. Go to https://render.com -> **New +** -> **Blueprint** -> pick your repo.
   Render reads `server/render.yaml` and creates a free web service.
   (Or: New -> Web Service, Root Directory `server`, Build `npm install`,
   Start `node server.js`, Instance type **Free**.)
3. In the service's **Environment** tab, copy the value of `STREAM_KEY`
   (auto-generated; you can set your own, 8+ characters).
4. Your URL looks like `https://secure-transmission-xxxx.onrender.com`.

Free-tier note: Render sleeps a free service after ~15 min with no traffic, and
takes ~30-60 s to wake. Open the dashboard URL once before your dive to wake it;
while the phone is streaming it stays awake.

## 3. Bake the URL + key into the APK (no typing on the phone)

In your GitHub repo: **Settings -> Secrets and variables -> Actions -> New repository secret**
and add two secrets:

- `RELAY_URL` = `https://secure-transmission-xxxx.onrender.com`
- `STREAM_KEY` = the key from Render's Environment tab

Then re-run the **Build APK** workflow and install the new APK. The app now
connects with those values built in, and hides the input boxes. (For a local
build, copy `relay.properties.example` to `relay.properties` instead.)

## 4. Run it

1. Phone, once, on dry land: open the app, tap **Start Streaming**, allow the
   camera and notification permissions. After this first time the app starts
   streaming by itself every time it is opened.
2. Seal the phone in the housing with the app running (the notification
   "Secure Transmission is running" confirms it).
3. Laptop (any network): open your Render URL, enter the stream key, **Connect**.

(`dashboard/dashboard.html` is the old direct-connect viewer. It only works when
the phone and laptop are on the same WiFi, using the phone's local IP.)

## Project structure

```
secure-transmission/
├── app/                        Android app (Kotlin, CameraX, NanoHTTPD)
│   └── src/main/java/com/humza/securetransmission/
│       ├── MainActivity.kt         start/stop UI, shows device IP
│       ├── CameraStreamService.kt  foreground service, camera capture, notification
│       └── MjpegServer.kt          embedded HTTP server, serves MJPEG stream
├── server/                     Free online relay (Node + WebSocket) + web dashboard
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
