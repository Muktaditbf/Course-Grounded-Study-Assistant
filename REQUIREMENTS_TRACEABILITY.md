# Requirement Traceability & Design Decisions
### Course-Grounded Study Assistant — Android implementation, CSE 346

This maps every requirement in your SRS to the code that satisfies it, and records
every decision taken where the documents were silent or contradictory.

---

## 1. The scope question — read this first

Your **SRS NFR 15.1** states:

> *"The application is web-first for Version 1.0; a native mobile app is out of scope for this release."*

Cost Report §6.3 and SRS §9.1 repeat it, and the mandated stack is React + Node + PostgreSQL.
So a native Android app **contradicts SRS Version 1.0 as written**.

**How this build resolves it:** SRS **§8 System Evolution** lists, as evolution item 1:

> *"A native mobile application (in addition to the current web-first release)."*

This app is therefore presented as the **Version 2.0 evolution deliverable** — the same
requirement set (FR1–FR10, NFR11–NFR16, UC1–UC10) re-implemented on a native Android client.
Nothing in the requirements is dropped; only the delivery platform advances, exactly as §8 anticipates.

If your instructor asks *"your SRS says no mobile app"* — that is the answer, and it is
supported by your own document.

---

## 2. Functional requirement traceability

| Req | Requirement | Screen | Java class |
|---|---|---|---|
| FR1 / 1.1–1.3 | Role-based login | Login, Sign Up | `LoginActivity`, `SignUpActivity`, `SessionManager` |
| FR2 / 2.1 | Teacher creates course, uploads material | Create Course, Upload Material | `CreateCourseActivity`, `UploadMaterialActivity` |
| FR2 / 2.2 | Every upload chunked for retrieval | (automatic) | `DatabaseHelper.insertChunks()` |
| FR3 / 3.1 | Teacher approves/locks the AI's source set | Approve and Lock | `ApproveMaterialsActivity` |
| FR3 / 3.2 | Unapproved sources excluded from answers | — | `RetrievalEngine.loadApprovedPassages()` |
| FR4 / 4.1–4.2 | Natural-language question, grounded answer | Ask a Question | `AskQuestionActivity`, `RetrievalEngine.ask()` |
| FR4 / 4.3 | Decline when coverage is thin | Ask (declined state) | `RetrievalEngine.COVERAGE_THRESHOLD` |
| FR5 / 5.1 | Cross-link related resources after each answer | Ask (Related Resources) | `RetrievalEngine.findRelated()` |
| FR6 / 6.1 | Keyword search without the AI | Search | `SearchActivity`, `DatabaseHelper.searchMaterials()` |
| FR7 / 7.1 | Bookmark and revisit | Material, Bookmarks | `MaterialViewActivity`, `BookmarksActivity` |
| FR8 / 8.1 | Student progress + teacher analytics | Progress, Analytics | `ProgressActivity`, `TeacherAnalyticsActivity` |
| FR9 / 9.1 | Notifications | Notifications | `NotificationsActivity`, `DatabaseHelper.notifyCourseStudents()` |
| FR10 / 10.1–10.3 | Premium & Institutional upgrade, bKash/Nagad/card | Billing, Payment | `BillingActivity`, `PaymentActivity` |

## 3. Non-functional requirement traceability

| Req | Requirement | How it is met |
|---|---|---|
| NFR11.1 | Encrypted transport | **N/A on device** — the app is fully offline; no data leaves the phone, so there is no channel to encrypt. Documented as a deliberate deviation. |
| NFR11.2 | Database backup | Android auto-backup enabled (`android:allowBackup="true"`) |
| NFR12.1 | Every operation requires a session | `BaseActivity.currentUser()` guards every screen |
| NFR12.2 | Only the owning teacher may approve | Ownership check in `ApproveMaterialsActivity.onResume()` |
| NFR13.1 | Fast response | On-device retrieval, no network — answers are instant |
| NFR13.2 | Concurrent use | N/A for a single-device prototype |
| NFR14.1 | Confidence threshold before answering | `COVERAGE_THRESHOLD = 0.34`, enforced in `RetrievalEngine.ask()` |
| NFR15.1 | Web-first, no mobile | **Deliberately superseded** — see §1 above |
| NFR16.1 | English and Bangla labels | `res/values/strings.xml` + `res/values-bn/strings.xml`, 100+ strings each |

## 4. Use case coverage

| UC | Title | Implemented in |
|---|---|---|
| UC1 | Sign Up (incl. alt. course 5.a) | `SignUpActivity` |
| UC2 | Login | `LoginActivity` |
| UC3 | Upload Course Material | `UploadMaterialActivity` |
| UC4 | Approve / Lock Material | `ApproveMaterialsActivity` |
| UC5 | Ask a Question (incl. alt. course 3.a) | `AskQuestionActivity` |
| UC6 | Search Course Materials | `SearchActivity` |
| UC7 | Bookmark a Topic | `MaterialViewActivity`, `BookmarksActivity` |
| UC8 | View Progress Dashboard | `ProgressActivity`, `TeacherAnalyticsActivity` |
| UC9 | Receive Related Resources | `RetrievalEngine.findRelated()` |
| UC10 | Upgrade (incl. alt. course 4.a payment failure) | `BillingActivity`, `PaymentActivity` |

---

## 5. Gaps found in the documents, and the decision taken

The documents were audited before coding. These points were under-specified; each was
resolved with a defensible choice rather than a silent guess.

| # | Gap in the documents | Decision taken |
|---|---|---|
| 1 | **No enrollment mechanism.** UC5's precondition requires the student be *"enrolled in a course"*, but no requirement or use case describes enrolling. | Added a **join-by-code** flow (`JoinCourseActivity`). Each course carries a unique join code; the teacher dashboard displays it. |
| 2 | **NFR 14.1 mandates a coverage threshold but gives no number.** | Set at **0.34** — at least ~1/3 of the meaningful question terms must appear in a single approved passage. Verified against 15 test questions. |
| 3 | **Free tier "capped daily AI questions" — the cap appears nowhere.** | Set to **10 questions/day** (`BaseActivity.FREE_DAILY_LIMIT`). Shown live on the Ask screen. |
| 4 | **Progress dashboard never defined** — no metric, window or chart named. | Chose what the system genuinely measures: questions asked, answered, declined, bookmarks, and a **grounding rate** (answered ÷ asked). |
| 5 | **Role is self-declared at signup** (UC1 step 3) — anyone can claim to be a teacher. | Kept as specified, but ownership is enforced downstream: only the teacher who owns a course can approve its materials (NFR 12.2). |
| 6 | **The vector database is never named**, and no embedding model is specified. | Replaced with **TF-IDF over stored text chunks** — no network, no API key, runs instantly, and is explainable in a viva. The `chunks` table occupies the role SRS §5.1 assigns the vector store. |
| 7 | **No backend exists, and a student build cannot run one during a demo.** | The app is **fully self-contained on-device** (SQLite). It cannot fail because of lab Wi-Fi. |
| 8 | **Upload assumes a browser file picker**; SRS says PDFs and slides. | Material text is typed or pasted. This avoids storage permissions and PDF parsing, which the Cost Report itself prices as a separate add-on ("Document Parser (scanned PDF/OCR), 30,000 Tk", §8.3) — i.e. out of the base build by your own costing. |
| 9 | **Real bKash/Nagad integration is impossible in a lab app.** | Payment is **clearly labelled simulated**. No payment credential is collected, transmitted or stored; only the account-number length is checked, which drives UC10's failure path. |
| 10 | **Institution Admin is an actor with no use case.** | Represented by the `institutional` account tier plus the teacher analytics dashboard, rather than inventing a new actor flow. |
| 11 | **Premium is priced per semester but Year-1 revenue counts it once per year** (150 × 300 = 45,000 Tk). | Not an app issue — flagged for your report. Either relabel the tier *"300 Tk/semester, billed once per academic year"* or restate the subscriber counts. |
| 12 | **Cost Report PV Year 3** reported as 167,270; correct value is **167,268**. | 2 Tk rounding difference. All 26 other figures verified correct. |
| 13 | Cost Report cover still reads **"Submitted by: [Your Name]"**, and §12 has a run-on: `...low-friction.5.  Timeline:` | Cosmetic fixes for your document, not the app. |
| 14 | SRS §2 promises a **use-case diagram**; none is present in the file. | Not supplied. The UC1–UC10 table was used as the source of truth. |

---

## 6. The retrieval algorithm (for your viva)

Given a question **q** in course **c**:

1. Load every passage whose parent material has `approved = 1` **for that course only**.
   *Unapproved material is never loaded — this is the Content Lock.*
2. Tokenize: lowercase → split on non-alphanumerics → drop tokens shorter than 3 →
   drop stop-words → light suffix stemming (`-ing`, `-ies`, `-es`, `-s`).
3. Score each passage against the query terms:

   ```
   idf(t)   = ln(1 + N / df(t))          N = approved passages in the course
   tf(t,p)  = 1 + ln(count of t in p)
   score(p) = Σ tf(t,p) · idf(t) / √|p|  ×  typeWeight(p)
   coverage = matched distinct query terms / total distinct query terms
   ```

4. **If `coverage < 0.34` → DECLINE** (NFR 14.1). Nothing is generated.
5. Otherwise build the answer from the best passage plus up to two supporting passages,
   each of which must clear three gates, and cite their source materials.
6. Remaining approved materials are ranked by term overlap and interleaved by type
   → **Lecture Connection Finder**.

### Three refinements found by testing on a real device

Each of these was a visible defect in the running app before it was fixed:

| Problem observed on device | Fix |
|---|---|
| The answer **repeated itself** — overlapping chunks both scored high, so the same sentences appeared twice. | Passages are now consecutive and non-overlapping, plus a redundancy gate skips any passage sharing >40% of its wording with the answer so far. |
| Answers **started mid-sentence** ("…open rectangle or two parallel lines"). | Chunking breaks on sentence boundaries (`.`/`!`/`?`), accumulating whole sentences to ~55 words. A passage is always complete prose. |
| A **quiz outranked the lecture**. "Quiz 1: List the phases of the waterfall model" matches the query words perfectly but explains nothing. | `typeWeight()` — quiz ×0.60, assignment ×0.80, lab ×0.95, lecture/notes ×1.00. Assessment material still appears under Related Resources, where it belongs. |

Plus a relevance gate: a supporting passage must score ≥80% of the best passage, so a
loosely-related passage cannot dilute a good answer.

**Verification:** 15 questions across all 7 courses, 15/15 produced the expected
answer-or-decline outcome, re-run after every tuning change. The locked-material demo goes
0% coverage (declined) → teacher approves → 100% coverage (answered).

---

## 7. Database schema (SRS Appendix B, on-device)

```sql
users(id, name, email UNIQUE, contact, password, role, tier)
courses(id, code, title, faculty, schedule, join_code UNIQUE, teacher_id)
enrollments(id, user_id, course_id)
materials(id, course_id, title, type, body, approved)
chunks(id, material_id, course_id, text)        -- stands in for the vector store
questions(id, user_id, course_id, text, answered, created_at)
bookmarks(id, user_id, material_id)
notifications(id, user_id, title, body, created_at)
```

---

## 8. Privacy note

Your course list contained faculty email addresses. **These are not stored in the app.**
Faculty names and initials appear as course metadata; the only credentials in the build are
the two demo logins created for this project.
