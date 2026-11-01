# Hue TV Sync

An Android TV app for the NVIDIA Shield that makes Philips Hue lights follow what's on screen.
After the one-time setup, it's a single button: **Turn ON** / **Turn OFF**.

## How it works

- **Screen sync:** the app watches a tiny copy of the screen and sends each light the colour
  from its side of the picture, about 50 times a second, through the Hue Entertainment API.
- **Mood lighting:** Netflix, Disney+, Prime Video, Max and similar apps block screen capture
  (the video shows as black to any app). For those, the lights switch to slow, ambient
  colours taken from the show's artwork or the app's own colours.

## One-time setup

1. **Hue app (phone):** Settings → Entertainment areas → create one containing the lights
   around your TV, and place them roughly where they are in the room.
2. **Shield:** Settings → Device Preferences → About → click *Build* 7 times to unlock
   Developer options. Then Developer options → turn on **Network debugging**. Note the
   Shield's IP address (Settings → Network & Internet).
3. **Build the app** on your PC (JDK 17 and the Android SDK live in `C:\Users\Jacob\Android`).
   The APK lands in `app\build\outputs\apk\debug\app-debug.apk`:
   ```
   $env:JAVA_HOME = "$env:USERPROFILE\Android\jdk17"; .\gradlew.bat assembleDebug
   ```
4. **Install it** (allow the debugging prompt on the TV the first time):
   ```
   powershell -ExecutionPolicy Bypass -File .\install.ps1 -ShieldIp 192.168.1.50
   ```
   This also grants the two permissions that let the button work in one click.
5. **On the Shield**, open **Hue TV Sync**. It finds your bridge, asks you to press the
   round button on top of the bridge once, then picks your entertainment area.

## Everyday use

Open Hue TV Sync → press **Turn ON**. Go watch something. Come back and press **Turn OFF**
when you're done (the lights return to how they were).

The main screen also has **Brightness** (Low / Medium / High) and **Set up again** if you
change bridges or entertainment areas.
