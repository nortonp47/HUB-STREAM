# Hub

A custom Android TV app for the Google TV Streamer: IPTV player, cable-style guide, and Stremio-style add-ons in one place. This is the Phase 0 scaffold.

## What works now
- Home screen with D-pad navigation
- Search screen with an on-screen keyboard drawn by the app (no system keyboard needed) and a Speak button
- Diagnostics screen: shows which key the star button sends, whether a speech recognizer exists, and which microphones apps can see

Everything else on the home screen is a placeholder until its phase is built.

## Get the APK built (one time setup)
1. On github.com create a new **public** repository (private repos can't be downloaded by the Downloader app without a login). Name it anything, for example `hub`.
2. Upload every file from this folder to the repository's `main` branch, including the hidden `.github` folder. Easiest: on your computer, unzip, then drag the contents into the repo page ("Add file" > "Upload files").
3. Open the **Actions** tab. The "Build APK" workflow runs on its own and takes about 5 minutes. If it doesn't start, pick the workflow and press "Run workflow".
4. When it finishes, the APK is at:
   `https://github.com/<your-username>/<repo>/releases/download/latest/app-release.apk`

If the build fails, open the failed run, copy the red error lines, and send them to me. I wrote this without being able to compile it, so a first-run fix is likely.

## Install on the Streamer
1. Install the **Downloader** app from the Play Store.
2. Settings > System > About > tap Build 7 times, then Settings > Apps > Security & restrictions > Unknown sources > allow Downloader.
3. In Downloader, enter the release link above and install.
4. Updates install over the old version.

## First tests (answers the star button and microphone questions)
1. Open **Hub**, choose **Diagnostics**.
2. Press the star button, the voice button and a few others. Anything that appears in the list can be used by the app. Anything that doesn't is kept by the system.
3. Note what "Microphones apps can see" and "Speech recognizer available" say, and tell me.

## Star button to voice search
Hub installs a second launcher entry called **Hub Voice Search**. Opening it starts listening immediately. Opening **Hub** normally shows the home screen. Once Diagnostics shows what the star button sends, it gets mapped to Hub Voice Search with a button-remapping app (Button Mapper) or the Streamer's own button settings.

## Notes
- The signing key in `app/hub.keystore` is only for sideloading this app so updates install cleanly. It is not secret and protects nothing else.
- Your IPTV login and add-on keys are never stored in this repository. They will be entered on the TV and kept on the device only.
- Cleartext HTTP is allowed because many IPTV panels don't use HTTPS.
