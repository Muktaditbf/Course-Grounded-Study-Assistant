# Study Assistant — Figma screens

26 SVG artboards generated from the live app's own design tokens
(`values/colors.xml`, `values-night/colors.xml`, `dimens.xml`), so the prototype and the
running app match rather than drifting apart.

Open `preview.html` in a browser to see them all on one contact sheet.

## Importing into Figma

1. Create a new Figma file.
2. Drag **all the `.svg` files in at once** — or *File → Place image…* and multi-select.
   Each file lands as its own frame, 390 × 844 (iPhone 14 size).
3. Select all frames → right-click → **Tidy up** to lay them out in a grid.

Optional, worth doing before you present:

- Select a frame → in the right panel set **Frame → iPhone 14** so it previews at device size.
- Use **Prototype** mode to connect the screens (see the flow below).
- Install the free **Inter** font if Figma prompts for it — that is the app's typeface.

## Why these import cleanly

- **Text stays editable.** Every label is a real `<text>` element, never an outlined path,
  so you can retype any copy directly in Figma.
- **Layers are named.** Each group carries a `data-name` (Figma reads it in preference to
  `id`), so you get `Hero gradient`, `Notification bell`, `Bottom navigation` instead of
  `Group 12`. Course cards are named after the course.
- **No filters, masks or `<use>`.** Figma rasterises those on import; avoiding them keeps
  every shape a live vector you can recolour.
- Gradients, rounded corners and stroke weights all survive as editable properties.

## The screens

| File | Screen |
| --- | --- |
| `00_StyleGuide` | Colour (light + dark), type scale, components, illustrations, card anatomy |
| `01_Login`, `02_SignUp` | Authentication |
| `03_StudentDashboard`, `03b_..._Dark` | Student home — illustrated course cards, bell + badge |
| `04_CourseHome` | One course, approved material only |
| `05_Ask_Empty`, `05b_Ask_Thinking` | Ask — suggestions, and the in-flight state |
| `06_Ask_Answered` | Grounded answer, 100% coverage, sources |
| `06b_Ask_General` | General-knowledge answer, no sources |
| `07_Ask_Error` | AI error state with Try again |
| `08_Search`, `08b_Search_Idle` | Results, and recent + filter chips |
| `09_Bookmarks` | Swipe-to-remove with undo snackbar |
| `10_Progress` | Stats, grounding rate, per-course breakdown |
| `11_TeacherDashboard` | Courses, join codes, pending-review badges |
| `12_ApproveLock`, `12b_RevokeConfirm` | **Teacher-Enforced Content Lock** |
| `13_UploadMaterial` | Upload slides / PDFs / notes |
| `14_Billing`, `15_Payment_Failed` | Plans, and a failed payment with its reason |
| `16_Settings`, `16b_Settings_Dark` | Theme, language, notifications, logout |
| `17_Notifications` | Alerts list |
| `18_JoinCourse` | Enrolment by join code |
| `19_Bangla_Dashboard` | Bangla localisation sample |

## Suggested prototype flow

```
01_Login → 03_StudentDashboard → 04_CourseHome → 05_Ask_Empty
                                              → 05b_Ask_Thinking → 06_Ask_Answered
03_StudentDashboard → 08b_Search_Idle → 08_Search
                    → 09_Bookmarks
                    → 10_Progress
                    → 16_Settings → 16b_Settings_Dark
01_Login (as teacher) → 11_TeacherDashboard → 13_UploadMaterial
                                            → 12_ApproveLock → 12b_RevokeConfirm
```

The teacher branch is the one to walk through slowly — `12_ApproveLock` and
`12b_RevokeConfirm` are where the Content Lock is visible.
