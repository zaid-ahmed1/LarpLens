# LarpLens

Android app for Meta AI glasses (no display): streams the glasses camera to the phone and plays
audio (including ElevenLabs TTS) back through the glasses speakers.

Step 1 of "Familiar Face" (look at a name tag, hear a whispered brief). So far: camera stream +
audio out. Next: name tag crop/OCR, corpus + matcher, cached TTS.

## Setup

1. Android 12+ phone with the Meta AI app, glasses paired, **Developer Mode** on in Meta AI.
2. `local.properties` (not committed):
   ```
   sdk.dir=/path/to/Android/sdk
   mwdat_application_id=0
   mwdat_client_token=0
   elevenlabs_api_key=sk_...          # dev only: compiled into the debug APK
   elevenlabs_voice_id=cCYjmrGZaI86GUJ7F2Nn
   elevenlabs_model_id=eleven_flash_v2_5
   ```
3. `./gradlew installDebug`

## Using it

- **Real glasses:** Register with Meta AI → Start camera (grants the glasses camera permission in
  Meta AI the first time) → live preview on the phone.
- **No glasses:** toggle *Mock glasses* → the phone's back camera stands in for the glasses camera.
- **Audio:** *Play test tone* (offline) or *Speak* (ElevenLabs). "Last route" shows where audio
  actually went; you want `Bluetooth A2DP · <glasses>`.

## How it works

| Piece | API |
|---|---|
| Camera | Meta Wearables DAT `mwdat-camera` 1.0.1: `DeviceSession` → `addCamera(StreamConfiguration(MEDIUM, 15fps))` → raw I420 frames → Bitmap |
| Audio out | No DAT API exists for this. Plain Android `AudioTrack` (USAGE_MEDIA) → phone routes it to the glasses over Bluetooth A2DP |
| TTS | ElevenLabs `POST /v1/text-to-speech/{voice}/stream?output_format=pcm_22050`, streamed into the AudioTrack |
| Mock | `MockDeviceKit` + `setCameraFeed(CameraFacing.BACK)` |

Don't enable HFP/communication-mode mic capture at the same time as playback: it forces the
glasses into mono call-quality audio.

## License note

Uses the Meta Wearables Device Access Toolkit under the
[Meta Wearables Developer Terms](https://wearables.developer.meta.com/terms). Public release needs
Meta's Integration Review.
