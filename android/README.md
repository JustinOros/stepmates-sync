# StepMates Sync — Android

Syncs your steps from Health Connect to StepMates automatically.

## How to Use

1. Open the app
2. Paste your StepMates Webhook URL (from your approval email)
3. Tap **Sync Now**
4. Grant Health Connect permission when prompted
5. Set your preferred auto-sync interval
6. Done — steps sync automatically in the background

Your webhook URL looks like:
`https://webhook-mgc3i5pbxq-uc.a.run.app?token=YOUR_TOKEN`

You can also find your webhook URL in StepMates → hamburger menu → Setup & Sync.

## Requirements

- Android 8.0+ (API 26+)
- A StepMates account at https://justinoros.github.io/step-tracker

## Download

[Download latest APK](https://github.com/JustinOros/step-tracker/releases/download/v1.1/StepMates-Sync-Android.apk)

## How to Build

1. Install [Android Studio](https://developer.android.com/studio)
2. Open the `android/` folder as a project
3. **Build → Generate Signed APK**
4. Create a keystore and save it somewhere safe
5. Upload the signed APK to GitHub Releases

## How it Works

- Reads last 7 days of steps from Health Connect
- POSTs to your personal StepMates webhook URL
- Auto-syncs in the background on your chosen schedule
- No login required — your webhook URL is your key
