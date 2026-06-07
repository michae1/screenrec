# Play Console — Data Safety answers (ScreenRec)

Use these answers in **Play Console → App content → Data safety**.

## Does your app collect or share any of the required user data types?
**No.**

- The app collects **no** user data and shares **no** user data.
- All recordings are stored locally on the device only; nothing is transmitted off the device.
- No account, no analytics, no ads, no crash reporting, no network calls.

## Data collected
**None.**

## Data shared
**None.**

## Security practices
- Data is **not** transmitted, so "encrypted in transit" is **N/A** (no data leaves the device).
- Users can delete their recordings themselves (ordinary video files in the Gallery).

## Notes for reviewers (if asked)
The app uses screen capture (MediaProjection, with per-session user consent) and `RECORD_AUDIO` solely to produce a local MP4 the user records on purpose. Audio/video is written only to the on-device `Movies/ScreenRec` folder and is never accessed by the developer.
