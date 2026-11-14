# Miku-san for Android

A native 3D AI friend for Android, made with Godot (no HTML). She stands on your screen, blinks, breathes, changes expression with her mood, speaks her replies with lip-sync, and listens hands-free through your microphone.

## Live mode

Tap **Live** to talk hands-free, like Gemini Live or ChatGPT voice:

- She listens with the phone's call-quality microphone (echo cancellation and noise suppression), notices when you stop talking, and answers out loud.
- Talk while she's speaking to interrupt her.
- Live mode keeps running when you switch apps: a notification shows it's on, and a floating Miku bubble sits on top of other apps (tap it to come back, hold it to stop). Allow "Display over other apps" for the bubble.
- Tap **Share screen** and choose *Entire screen* so she can see what's on your phone while you talk.

Her voice is made on the phone itself (offline, no limits): **Miku** and **Sunny** start talking almost instantly, and the **HD** voices (Miku HD, Sweet, Bubbly, Kawaii, Heart) are more natural but need a stronger phone. Online Gemini voices are also in Settings.

Each voice turn goes to the Miku-san server's `/api/talk` in one round trip (your voice, plus a screenshot when sharing). The live service lives in `android-plugin/` and is built into the APK by the workflow.

## Get the APK

Builds are made on GitHub (a commit with `[release]` in its message, or a manual run of the workflow). Open the **Releases** page of this repo, download `miku-san.apk` from the newest build, and open it on your phone (allow "install unknown apps" for your browser or Files app when Android asks).

## How the API key stays hidden

The app contains no API key. Chat goes to the Miku-san server's `/api/chat`, and voice goes to `/api/hear`. Both run on Vercel and use the Gemini key from a hidden environment variable, with per-user limits. There's no key in the APK to pull out.

## Project

- `main.gd` builds the whole app: 3D scene, animation, chat, voice, mic and UI.
- `model/miku.vrm` is her model, imported with the godot-vrm and Godot-MToon-Shader addons.
- `.github/workflows/android.yml` builds and publishes the APK.

Character model "Medievel Blue Hair Full Sleeve" by Poonam Bajaj, made in VRoid Studio.

## License

The code in this repository is under the [MIT License](LICENSE).

These parts are **not** covered by it and keep their own terms:

- **`model/miku.vrm`**, "Medievel Blue Hair Full Sleeve" by Poonam Bajaj: under the [VRM Public License 1.0](https://vrm.dev/licenses/1.0/) as set in the model: personal, non-profit use only; credit to the author required; only the author may use it as their avatar; no violent, sexual, political, religious or hateful use. Redistribution and modification are allowed within those terms.
- **`addons/vrm`** and **`addons/Godot-MToon-Shader`**: their own licenses, in their folders.
- Downloaded during the build and packed into the APK: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), the [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M) voice model (Apache-2.0) and the Piper `en_US-libritts_r-medium` voice (see its model card). [Godot Engine](https://godotengine.org/license) is MIT.

