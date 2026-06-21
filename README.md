# StepMates Sync — Android Companion App

Syncs your steps from Health Connect to StepMates automatically.

## How to Use

1. Open the app
2. Paste your StepMates Webhook URL (from your approval email)
3. Tap **Sync My Steps**
4. Grant Health Connect permission when prompted
5. Done! Your steps are synced

Your webhook URL looks like:
`https://webhook-mgc3i5pbxq-uc.a.run.app?token=YOUR_TOKEN`

## Requirements
- Android 8.0+ (API 26+)
- Health Connect installed ([Get it on Play Store](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata))
- A StepMates account at https://justinoros.github.io/step-tracker

## How to Build

1. Install [Android Studio](https://developer.android.com/studio)
2. Open this project
3. **Build → Generate Signed APK**
4. Create a keystore (save it somewhere safe!)
5. Upload the signed APK to GitHub Releases

## How it Works

- Reads last 7 days of steps from Health Connect
- POSTs directly to your personal StepMates webhook URL
- No login required — your webhook URL is your key
- Saves your webhook URL so you only need to paste it once
