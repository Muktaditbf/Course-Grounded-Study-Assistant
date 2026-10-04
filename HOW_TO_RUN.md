# How to Run — Course-Grounded Study Assistant

An Android app (Java, XML layouts) backed by Firebase Authentication and Cloud Firestore.

## 1. Firebase (once)

1. Create a project at [console.firebase.google.com](https://console.firebase.google.com) (free Spark plan).
2. Add an **Android app** with package name `com.seu.studyassistant`.
3. Download `google-services.json` into `StudyAssistant/app/` (git-ignored).
4. **Authentication → Sign-in method:** enable **Email/Password**.
5. **Firestore Database:** create the `(default)` database, Standard edition, production mode, region `asia-south1` (Mumbai) for Bangladesh.
6. **Firestore → Rules:** paste the whole of `StudyAssistant/firestore.rules` and click **Publish**. Re-publish whenever that file changes.

### Google sign-in (optional)

1. **Authentication → Sign-in method → Add new provider → Google:** enable it and pick a support email.
2. **Project settings → Your apps → Add fingerprint:** add the SHA-1 and SHA-256 of the signing key. Get them with:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -storepass android
   ```
3. Download `google-services.json` again and replace the old one, then rebuild.

## 2. Build

Open the **`StudyAssistant`** folder (not the repository root) in Android Studio and press **Run**. From a terminal:

```bash
cd StudyAssistant
./gradlew assembleRelease   # optimised APK: app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug     # debug APK, slower on the phone
```

Use the **release** build on real phones: it is shrunk and optimised, and noticeably faster. Without a release keystore in `local.properties` it is signed with the debug key, which is fine for testing.

### Optional settings in `StudyAssistant/local.properties`

```properties
OPENAI_API_KEY=your_key             # AI answers; without it the app shows matching passages instead
AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/chat/completions
AI_MODEL=gemini-flash-lite-latest
RELEASE_STORE_FILE=release.jks      # only for a Play Store release
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

A key in `local.properties` is compiled into the APK, so never share or upload an APK built with your personal key.

## 3. Use

1. **Teacher:** sign up, open the link in the verification email, tap **I've verified my email**. Create a course; its **join code** is shown on the dashboard. Open the course → **Manage → Upload material**, tick **Approve now** to share it.
2. **Student:** sign up and verify, then **Join course** with the teacher's code. Approved materials appear within seconds; tapping one opens the original PDF, slides or document.
3. **Ask a Question** (in a course) or the **Ask AI** chat: answers come only from approved material, with numbered sources. A question the material does not cover is declined.

## 4. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| "Firebase is not set up" | No `google-services.json` at build time | Add it to `StudyAssistant/app/` and rebuild |
| "Could not sync with the cloud: permission denied" | Rules not published, or old rules | Paste and publish `firestore.rules` again |
| A course does not appear for a student | Not joined, or material not approved | Join with the code; approve the material |
| "Google sign-in is not enabled for this app yet" | Google provider or fingerprints missing | Do the Google sign-in steps above |
| Verification email missing | Spam filter | Check spam, or tap **Resend email** |
