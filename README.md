# Course-Grounded Study Assistant

An Android study assistant that answers students' questions **only from teacher-approved course material**. If the approved material doesn't cover a question, the app declines instead of guessing.

Built for **CSE 346 (Software Engineering)** as the native Android evolution (SRS §8) of the project defined in the Problem Definition Document, SRS and Cost Finding Report.

![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/language-Java-orange)
![Min SDK](https://img.shields.io/badge/minSdk-24-blue)
![Target SDK](https://img.shields.io/badge/targetSdk-37-blue)

## Features

| Feature | Description |
|---|---|
| **Content Lock** | Answers come only from material the teacher has approved. If the material does not cover a question, the app declines instead of guessing. |
| **Source citations** | Every answer cites the passages it used, like [1] and [2], and each source opens the original file. |
| **Original files** | PDF, PowerPoint and Word uploads are shown to students as real pages and slides. |
| **Accounts** | Firebase sign-in with email verification, password reset, change password, and Google sign-in. |
| **Lecture Connection Finder** | Lists related lectures, assignments and quizzes from the same course alongside each answer. |
| **Teacher workflow** | Create courses, upload PDF material, approve or revoke it, share join codes, view analytics. |
| **Student workflow** | Join courses by code, ask questions, search, bookmark materials, track progress. |
| **Free and paid tiers** | Daily question counter for the free tier, with an upgrade and payment flow. |
| **Bilingual UI** | English and Bangla (বাংলা), with light and dark themes. |

## Designs

The Figma designs are in [`Figma_Screens/`](Figma_Screens/) as editable SVG files.

## How the Content Lock works

1. A teacher uploads material. It is text-extracted and chunked, and starts as **Pending**.
2. A student asks a question. The app retrieves matching chunks from **approved** material only.
3. If the approved passages cover the question, the AI answers from them only and cites them. Otherwise the app declines instead of guessing.
4. When the teacher approves the material, the same question gets an answer.

## Tech stack

- **Language:** Java, with XML layouts
- **Backend:** Firebase Authentication (email and password) and Cloud Firestore, mirrored into SQLite on the device
- **UI:** Material Components, RecyclerView, bottom navigation
- **PDF parsing:** [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android)
- **Networking:** OkHttp (OpenAI API for general answers)
- **Build:** Gradle (Kotlin DSL), Android Gradle Plugin 9, JDK 21
- **SDK:** minSdk 24, targetSdk 37

## Getting started

### Prerequisites
- Android Studio (recent version) with Android SDK 37
- JDK 21
- An Android device or emulator running Android 7.0 (API 24) or newer

### Build and run
1. Clone the repository:
   ```bash
   git clone https://github.com/Muktaditbf/Course-Grounded-Study-Assistant.git
   ```
2. In Android Studio, choose **File → Open** and select the **`StudyAssistant`** folder (not the repo root).
3. *(Optional)* To enable general AI answers, add your key to `StudyAssistant/local.properties`:
   ```properties
   OPENAI_API_KEY=your_key_here
   ```
   This file is git-ignored, so the key stays on your machine. Without a key, the app still works and shows a "key not set" message for AI-only features.
4. Wait for Gradle sync, then press **Run ▶**.

A prebuilt debug APK is included as [`StudyAssistant.apk`](StudyAssistant.apk) for quick installation.

### Firebase setup (required)

Accounts and course data live in Firebase, so the app needs a Firebase project:

1. In the [Firebase console](https://console.firebase.google.com) create a project and add an **Android app** with package name `com.seu.studyassistant`.
2. Enable **Authentication → Sign-in method → Email/Password**.
3. Create a **Firestore Database** (production mode).
4. Download `google-services.json` and put it at `StudyAssistant/app/google-services.json` (it is git-ignored).
5. Publish the security rules: `firebase deploy --only firestore:rules`, or paste `StudyAssistant/firestore.rules` into **Firestore → Rules**.

Without `google-services.json` the project still builds, and Login shows "Firebase is not set up".

### Uploaded files

Teachers' original files (PDF, PPTX, DOCX, up to 15 MB) are stored inside Firestore in pieces, so no paid Cloud Storage plan is needed and the same security rules protect them. Students see them in the app's own viewer: PDF pages exactly, PowerPoint as rendered slides, and Word as a formatted page. An **Open in…** button hands the file to another app (PowerPoint, WPS, Google Slides) when one is installed. Charts, SmartArt and animations are not drawn by the built-in slide viewer, so a deck that relies on them should be uploaded as PDF.

### First run

There are no built-in accounts. Sign up as a **teacher** and verify your email, create a course, and upload material with **Approve now** ticked. On a second phone, sign up as a **student**, verify, and **Join course** with the code shown on the teacher dashboard.

### Try the Content Lock
1. Upload a lecture but leave **Approve now** unticked.
2. As the student, ask a question that lecture answers. The app declines: no approved material covers it.
3. As the teacher, tap **Approve** on the lecture.
4. Ask again. The app answers from the lecture and cites it.

## Project structure

```
.
├── StudyAssistant/            # Android Studio project
│   └── app/src/main/
│       ├── java/com/seu/studyassistant/
│       │   ├── adapter/       # RecyclerView adapters
│       │   ├── data/          # Local data and storage
│       │   ├── engine/        # Retrieval, coverage and OpenAI client
│       │   ├── model/         # Data models
│       │   └── ui/            # Activities
│       └── res/               # Layouts, strings (en, bn), themes
├── Figma_Screens/             # Exported UI designs (SVG)
├── HOW_TO_RUN.md              # Setup, build and troubleshooting
├── REQUIREMENTS_TRACEABILITY.md  # SRS requirements mapped to code
└── StudyAssistant.apk         # Prebuilt debug APK
```

## Documentation

- [How to run](HOW_TO_RUN.md)
- [Requirements traceability](REQUIREMENTS_TRACEABILITY.md): maps FR1–FR10, NFR11–NFR16 and UC1–UC10 to the code
- [Project guide (PDF)](Study_Assistant_Project_Guide.pdf)

## Notes

- Never commit `local.properties` or APKs built with a real API key.
- The app is a coursework project, and the payment flow is simulated.
