# DynamicLiveLyrics — About & Security Info

### What is this app?
I built **DynamicLiveLyrics** as an open-source, non-root live lyrics widget for Android. It brings real-time, word-by-word synced karaoke lyrics with customizable themes (HyperOS Blur and Material You), custom fonts, NKB button animations, a compact silent live lyrics notification, and 30 FPS live rendering to **any Android launcher** (Pixel Launcher, Xiaomi HyperOS, Samsung OneUI, Lawnchair, Lunaris, Nova, etc.).

---

### Why is this APK safe to install?
With all the sketchy third-party APKs floating around lately, being careful about what you install on your phone is completely normal. Here is a simple, honest breakdown of how DynamicLiveLyrics works and why it is safe to use:

#### 1. No Root Needed
Unlike system mods or root modules, DynamicLiveLyrics runs inside the normal Android app sandbox. It doesn't need root access, Magisk/KernelSU modules, or any hidden system permissions.

#### 2. Permissions Used (and why)
The app only asks for the standard Android permissions needed to make a live music widget work:

- **Notification / Media Listener Access (`BIND_NOTIFICATION_LISTENER_SERVICE`)**
  - **Why it needs it:** Android doesn't let background widgets see what song is playing unless they use the official [MediaSessionManager](https://developer.android.com/reference/android/media/session/MediaSessionManager) API through Notification Access.
  - **What it reads:** Only active media sessions from your music apps (Spotify, YouTube Music, Apple Music, Metrolist, Musicolet, Poweramp, etc.) to get the song title, artist, album cover, playback time, and like status.
  - **What it NEVER touches:** Your personal notifications, chats, WhatsApp messages, SMS, emails, or bank OTPs are completely ignored and never read.

- **Internet Access (`android.permission.INTERNET`)**
  - **Why it needs it:** Only to fetch synced lyrics for the song you're currently playing from public lyrics databases.
  - **No tracking:** There are zero analytics SDKs, zero ads, no telemetry, and nothing from your phone is ever uploaded anywhere.

- **Foreground Service & Notifications (`FOREGROUND_SERVICE_MEDIA_PLAYBACK` & `POST_NOTIFICATIONS`)**
  - Required by Android 14+ so the widget can smoothly animate the active words, wavy progress bar, NKB button effects, and the optional **Silent Live Lyrics Notification** while your music is playing.

#### 3. Permissions the app DOES NOT ask for
- **No Storage Access:** It never asks for `MANAGE_EXTERNAL_STORAGE` or access to your photos/files. When you load a custom `.ttf` or `.otf` font, it uses Android's built-in system file picker so the app only sees the single font file you picked.
- **No Accessibility Service:** Never observes your screen or keystrokes.
- **No Draw Over Other Apps / Overlay Permission:** Works as a normal home screen widget and silent notification without invasive floating overlays.
- **No Microphone, Camera, Contacts, or Location Access.**

---

### Open-Source References & Credits
I used these awesome open-source projects as references while building the lyrics engine and animations:
- **[mostafaalagamy/Metrolist](https://github.com/mostafaalagamy/Metrolist)** — Used as a reference for the left-to-right word glow sweep and line-to-line lyric animations.
- **[BetterLyrics/better-lyrics](https://github.com/BetterLyrics/better-lyrics)** — Reference for TTML syllable-synced lyrics parsing.
- **[tranxuanthang/lrclib](https://github.com/tranxuanthang/lrclib)** — Community synced LRC lyrics database API.

---

### Performance & Battery
- **30 FPS Live Widget Updates:** Uses `AppWidgetManager.partiallyUpdateAppWidget` so only the widget canvas updates each frame without reloading the layout.
- **Silent Compact Live Lyrics Notification:** Optional compact 2-line notification (with blurred & faded top/bottom half-lines and word-synced middle line) that stays completely silent (`IMPORTANCE_LOW`) and never pops up over your screen.
- **Smart Sleep (0% Idle CPU):** The widget only renders frames when music is actually playing and your screen is on (`PowerManager.isInteractive`). As soon as you pause your music or turn off your screen, the loop pauses and uses **0% CPU**.

*Created by **Sagar Mahto** • 100% Non-Root • Free & Open-Source*
