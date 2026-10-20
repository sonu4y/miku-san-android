# Miku-san for Android

A native 3D AI friend for Android, made with Godot (no HTML). She stands on your screen, blinks, breathes, changes expression with her mood, speaks her replies with lip-sync, and listens hands-free through your microphone.

## Get the APK

Every push to `main` builds the app on GitHub. Open the **Releases** page of this repo, download `miku-san.apk` from the newest build, and open it on your phone (allow "install unknown apps" for your browser or Files app when Android asks).

## How the API key stays hidden

The app contains no API key. Chat goes to the Miku-san server's `/api/chat`, and voice goes to `/api/hear`. Both run on Vercel and use the Gemini key from a hidden environment variable, with per-user limits. There's no key in the APK to pull out.

## Project

- `main.gd` builds the whole app: 3D scene, animation, chat, voice, mic and UI.
- `model/miku.vrm` is her model, imported with the godot-vrm and Godot-MToon-Shader addons.
- `.github/workflows/android.yml` builds and publishes the APK.

Character model "Medievel Blue Hair Full Sleeve" by Poonam Bajaj, made in VRoid Studio.
