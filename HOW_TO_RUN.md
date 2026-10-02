# Course-Grounded Study Assistant — How to Open and Run

Android app (Java + XML layouts) implementing the PDD, SRS and Cost Finding Report for CSE 346.

## Design

Modern Material-style interface built on one design system:

- **Indigo → violet gradient headers** with rounded bottoms
- **22dp cards** with a colour accent stripe per course, on a near-white ground
- **Pill buttons**, chips for status (Locked / Pending / coverage %)
- **Bottom navigation** — Home · Search · Bookmarks · Progress · Settings (notifications open from the bell in the dashboard header)
- Semantic colour: **green = teacher-approved**, **amber = declined**

Every token lives in `res/values/colors.xml`, `dimens.xml` and `themes.xml`, and the
Figma screens in `Figma_Screens\` use exactly the same values.

## Verified working

This app was installed and driven on a real Android 14 device (emulated Pixel 6) before
being handed over. Confirmed working end to end, with **zero crashes** across every flow:

- Login as student → 7 real courses load with faculty, schedule and approved counts
- Login as teacher → role-based routing to the teacher dashboard, join codes, pending badges
- Ask a question with no approved coverage → **declines at 0%**, states the 34% threshold
- Teacher taps **Approve** → material flips Pending → Locked
- Student asks the same question → **now answers at 100%**, citing the approved material
- Sources and Related Resources render; free-tier counter decrements 5 → 4 → 3 …

---

## 1. Open the project in Android Studio

1. Open **Android Studio**
2. **File → Open**
3. Select this folder:
   `C:\Users\MKTD\OneDrive\Desktop\ISD\StudyAssistant`
4. Click **OK**, then wait for *"Gradle sync finished"* at the bottom

> Open the **`StudyAssistant`** folder, not the `ISD` folder. Opening `ISD` will not be recognised as a project.

Everything the build needs is already installed and verified — Gradle 9.6, AGP 9.4, SDK 37, JDK 21.

---

## 2. Prepare your phone (once)

1. **Settings → About phone**
2. Tap **Build number** 7 times → *"You are now a developer"*
3. Go back → **System → Developer options**
4. Turn on **USB debugging**
5. Xiaomi / Oppo / Vivo / Realme users: also turn on **Install via USB**

---

## 3. Connect and run

1. Plug the phone into the PC with a **data cable** (a charge-only cable will not work)
2. On the phone, a dialog appears: **Allow USB debugging?**
   → tick **Always allow from this computer** → **OK**
3. In Android Studio, your phone appears in the device dropdown at the top
4. Press the green **Run ▶** button (or `Shift + F10`)

The app builds, installs and launches on your phone.

### If the phone does not appear

Open a terminal and run:

```bash
"C:\Users\MKTD\AppData\Local\Android\Sdk\platform-tools\adb.exe" devices
```

| Output | Meaning | Fix |
|---|---|---|
| `<serial>  device` | Working | Press Run |
| `<serial>  unauthorized` | You missed the Allow dialog | Unplug, replug, tap Allow |
| Empty list | Cable or driver | Try another cable / USB port; enable *Install via USB* |

---

## 4. Login accounts

| Role | Email | Password |
|---|---|---|
| Teacher | `teacher@seu.edu.bd` | `teacher123` |
| Student | `student@seu.edu.bd` | `student123` |

The student is already enrolled in all 7 of your real registered courses.

---

## 5. Demo script for your teacher

### A. The Content Lock (the main feature — PDD §6.1, NFR 14.1)

**This sequence is verified to work — 15/15 automated checks passed.**

1. Login as **student** → open **CSE346.14**
2. Tap **Ask a Question**
3. Type: **`What is equivalence partitioning?`**
4. → App shows **"No approved material covers this"** with *Coverage 0%, below the 34% minimum*

   *Say: "The AI refuses to guess. The material on testing exists, but the teacher has not approved it."*

5. **Logout** → login as **teacher**
6. Tap **CSE346.14** → find **"Lecture 4: Software Testing Fundamentals"** (Pending)
7. Tap **Approve**
8. **Logout** → login as **student** → same course → ask the **same question** again
9. → Now it **answers**, at **100% coverage**, citing *Lecture 4: Software Testing Fundamentals*

Other verified decline→answer pairs:

| Course | Question | Locked material to approve |
|---|---|---|
| CSE346.14 | `What is the level zero DFD and what is balancing?` | Notes: Data Flow Diagrams |
| CSE346.14 | `Difference between unit testing and integration testing` | Lecture 4: Software Testing |
| CSE365.11 | `What is overfitting and underfitting?` | Notes: Machine Learning Basics |

### B. The Lecture Connection Finder (PDD §6.2, UC9)

Ask in CSE346.14: **`What is an actor in use case modelling?`**

→ Answers from *Lecture 3*, then automatically lists **Related Resources** from the same
course spanning lecture, assignment and quiz — without the student searching.

### C. Questions that answer immediately (safe to demo)

| Course | Question | Coverage |
|---|---|---|
| CSE346.14 | What are the phases of the waterfall model? | 100% |
| CSE346.14 | What is the difference between functional and non functional requirements? | 100% |
| CSE346.14 | How do you calculate present value and payback period? | 80% |
| CSE383.2 | Explain third normal form and transitive dependency | 100% |
| CSE384.6 | What is the difference between a left outer join and an inner join? | 100% |
| CSE341.8 | What does the transport layer do in the OSI model? | 50% |
| CSE342.14 | How many usable hosts are in a slash 26 subnet? | 75% |
| CSE365.11 | What makes A star search optimal? | 50% |
| ETE282.15 | What is the modulation index in amplitude modulation? | 100% |

### D. Other features to show

- **Search** (UC6) — search `normalization`, results come from Database Design
- **Bookmark** (UC7) — open any material → Bookmark → see it in Bookmarks
- **Progress** (UC8) — counters and grounding rate update as you ask
- **Join Course** — student joins with code `ISD346`
- **Upload** (UC3) — teacher adds material, chunked instantly for retrieval
- **Billing** (UC10) — Upgrade → enter a short account number → **payment fails** →
  enter 11 digits → succeeds and the tier changes
- **Bangla** — switch the phone language to বাংলা; every label translates (NFR 16.1)

---

## 6. Showing the XML layouts

In Android Studio, expand `app → res → layout`. 17 layout files:

```
activity_splash.xml            activity_login.xml           activity_sign_up.xml
activity_student_dashboard.xml activity_join_course.xml     activity_course_home.xml
activity_ask_question.xml      activity_material_view.xml   activity_search.xml
activity_list.xml              activity_progress.xml        activity_teacher_dashboard.xml
activity_create_course.xml     activity_upload_material.xml activity_teacher_analytics.xml
activity_billing.xml           activity_payment.xml
view_header.xml                item_row.xml
```

Click any file, then use the **Design / Split / Code** tabs at the top right to show
the visual preview beside the XML.

---

## 7. Figma designs

`ISD\Figma_Screens\` holds 16 SVG files.

**To import:** open Figma → **File → Import** (or drag the files onto the canvas).
Each screen arrives as editable layers — real text, real vectors, real colours.

Open `preview.html` in a browser to see them all at once.
