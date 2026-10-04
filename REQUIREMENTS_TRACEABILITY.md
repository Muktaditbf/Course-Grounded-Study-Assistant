# Requirement Traceability & Design Decisions
### Course-Grounded Study Assistant — Android implementation, CSE 346

Maps the SRS requirements to the code that satisfies them, and records the decisions taken where the documents were silent or contradictory.

---

## 1. Scope

SRS NFR 15.1 makes Version 1.0 web-first, with a native mobile app out of scope. SRS §8 (System Evolution) lists "a native mobile application" as evolution item 1. This app is presented as that evolution: the same requirement set (FR1–FR10, NFR11–NFR16, UC1–UC10) on a native Android client, backed by Firebase.

## 2. Architecture

| Layer | Implementation |
|---|---|
| Identity | Firebase Authentication: email/password with verification, password reset, Google sign-in |
| Shared data | Cloud Firestore (users, courses, join codes, enrolments, materials, file pieces, questions, bookmarks, notifications) |
| Access control | `StudyAssistant/firestore.rules`, enforced by the server |
| Local data | SQLite mirror of the user's slice of Firestore (`DatabaseHelper`), kept current by `SyncManager` |
| Original files | Stored in Firestore in 900 KB pieces under each material (free plan, same rules) |
| Retrieval | On-device BM25 over approved passages (`RetrievalEngine`) |
| Answers | LLM restricted to the retrieved passages, with citations (`OpenAiClient.askGrounded`) |
| Document viewer | PDF via `PdfRenderer`; PPTX via `PptxRenderer`; DOCX via `DocxHtml` |

## 3. Functional requirements

| Req | Requirement | Code |
|---|---|---|
| FR1 / 1.1–1.3 | Role-based login | `LoginActivity`, `SignUpActivity`, `VerifyEmailActivity`, `CloudRepo` |
| FR2 / 2.1 | Teacher creates, edits and deletes courses; uploads, edits and deletes material | `CreateCourseActivity`, `UploadMaterialActivity`, `ApproveMaterialsActivity`, `MaterialViewActivity` |
| FR2 / 2.2 | Every upload chunked for retrieval | `DatabaseHelper.insertChunks()` |
| FR3 / 3.1 | Teacher approves or revokes the AI's source set | `ApproveMaterialsActivity` |
| FR3 / 3.2 | Unapproved sources excluded from answers | Firestore rules (students can read only approved material) and `RetrievalEngine` (`approved = 1`) |
| FR4 / 4.1–4.2 | Natural-language question, grounded answer | `AskQuestionActivity`, `ChatActivity`, `RetrievalEngine`, `OpenAiClient.askGrounded()` |
| FR4 / 4.3 | Decline when coverage is thin | `RetrievalEngine.COVERAGE_THRESHOLD`, plus the model's `NOT_IN_MATERIAL` reply |
| FR5 / 5.1 | Related resources after each answer | `RetrievalEngine.findRelated()` |
| FR6 / 6.1 | Keyword search without the AI | `SearchActivity`, `DatabaseHelper.searchMaterials()` |
| FR7 / 7.1 | Bookmark and revisit | `MaterialViewActivity`, `BookmarksActivity` |
| FR8 / 8.1 | Student progress and teacher analytics | `ProgressActivity`, `TeacherAnalyticsActivity` |
| FR9 / 9.1 | Notifications | `NotificationsActivity`, `DatabaseHelper.notifyCourseStudents()` |
| FR10 / 10.1–10.3 | Premium and Institutional upgrade | `BillingActivity`, `PaymentActivity` (simulated payment) |

## 4. Non-functional requirements

| Req | Requirement | How it is met |
|---|---|---|
| NFR11.1 | Encrypted transport | All Firebase and AI traffic uses HTTPS/TLS |
| NFR11.2 | Database backup | Firestore is replicated by Google; scheduled backups can be enabled on the Blaze plan |
| NFR12.1 | Every operation requires a session | Firebase session checked in `BaseActivity.currentUser()`; rules require a verified sign-in |
| NFR12.2 | Only the owning teacher may approve | Firestore rules check `teacherUid` on the course and material; the UI checks ownership too |
| NFR13.1 | Fast response | Local mirror for reads, retrieval cached and off the main thread, optimised release build |
| NFR13.2 | Concurrent use | Firestore serves many clients at once |
| NFR14.1 | Confidence threshold before answering | Coverage threshold 0.34 over the top passages; the model must reply `NOT_IN_MATERIAL` when the passages do not answer |
| NFR15.1 | Web-first | Superseded by SRS §8 (see section 1) |
| NFR16.1 | English and Bangla | `res/values/strings.xml` and `res/values-bn/strings.xml`; retrieval tokenises Bangla text |

## 5. Use cases

| UC | Title | Code |
|---|---|---|
| UC1 | Sign Up (with email verification) | `SignUpActivity`, `VerifyEmailActivity` |
| UC2 | Login (email, Google; forgot password) | `LoginActivity` |
| UC3 | Upload Course Material | `UploadMaterialActivity` |
| UC4 | Approve / Lock Material | `ApproveMaterialsActivity` |
| UC5 | Ask a Question (incl. decline 3.a) | `AskQuestionActivity` |
| UC6 | Search Course Materials | `SearchActivity` |
| UC7 | Bookmark a Topic | `MaterialViewActivity`, `BookmarksActivity` |
| UC8 | View Progress Dashboard | `ProgressActivity`, `TeacherAnalyticsActivity` |
| UC9 | Receive Related Resources | `RetrievalEngine.findRelated()` |
| UC10 | Upgrade (incl. payment failure 4.a) | `BillingActivity`, `PaymentActivity` |

## 6. Decisions where the documents were silent

| # | Gap | Decision |
|---|---|---|
| 1 | No enrolment mechanism | Join by code (`JoinCourseActivity`); codes are unique and fixed once created |
| 2 | Coverage threshold has no number | 0.34 of the meaningful question terms, measured over the top three passages |
| 3 | Free-tier daily cap has no number | 10 questions per day (`BaseActivity.FREE_DAILY_LIMIT`), covering Ask and chat |
| 4 | Progress dashboard undefined | Questions asked, answered, declined, bookmarks, and grounding rate |
| 5 | Role is self-declared | Kept as specified; ownership is enforced by the rules |
| 6 | No vector database named | BM25 lexical retrieval on the device; explainable and needs no extra service |
| 7 | Payment integration | Simulated and labelled as such; no payment data is collected |
| 8 | File storage | Original files stored in Firestore pieces, which keeps the project on the free plan |

## 7. Retrieval and answering

Given a question in a course (or across all enrolled courses, in the chat):

1. Load the approved passages, each tagged with its material's title. Unapproved material never reaches the device.
2. Tokenise: Unicode letters and digits, lower-case, stop words removed, light English stemming ("databases" and "database" meet). Bangla words are kept whole.
3. Rank with BM25 (k1 = 1.2, b = 0.75), plus a bonus for adjacent query words found adjacent in the passage. Quizzes ×0.60, assignments ×0.80, labs ×0.95, lectures and notes ×1.00.
4. Coverage = share of distinct query terms found in the top three passages. Below 0.34 → **decline**, with no AI call.
5. Send up to five ranked passages to the model, numbered, with the instruction to answer only from them, cite them as [n], and reply `NOT_IN_MATERIAL` if they do not contain the answer.
6. Show the answer with its cited sources; each source opens the original file. With no AI available, the matching passages themselves are shown.

## 8. Data model (Firestore)

```
users/{uid}                    name, email, contact, role, tier
courses/{id}                   code, title, faculty, schedule, joinCode, teacherUid
joinCodes/{CODE}               courseId, courseCode, teacherUid
enrollments/{uid}_{courseId}   userUid, courseId, teacherUid
materials/{id}                 courseId, teacherUid, title, type, body, approved,
                               fileName, fileMime, fileSize, fileChunks, fileVersion
materials/{id}/file/{n}        data (bytes)
questions/{id}                 userUid, courseId, teacherUid, text, answered, createdAt
bookmarks/{uid}_{materialId}   userUid, materialId
notifications/{id}             userUid, courseId?, title, body, createdAt
```

## 9. Privacy

Faculty email addresses are not stored. User profiles hold only name, email, optional contact number, role and tier. Passwords are handled by Firebase Authentication and never reach the app's database.
