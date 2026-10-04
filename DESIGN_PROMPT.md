# Design Prompt — Course-Grounded Study Assistant

A prompt for AI design tools (Figma AI, Galileo, Uizard, v0, Stitch), adapted from a generic "modern 2026" prompt to this app's real screens and features.

## The prompt

> Redesign the Android app **Course-Grounded Study Assistant**, a university study companion where teachers upload and approve course material (PDF, PowerPoint, Word) and students study it with an AI assistant called **ViVi**. Use **Material 3 Expressive with a frosted-glass ("Liquid Glass") layer**, edge-to-edge, mobile-first, and keep every existing feature.
>
> **Navigation.** Replace the bottom navigation with a **floating pill-shaped glass bar** inset 16dp from the screen edges, lifted above the gesture bar. It holds five tabs, each an icon with a short label: **Home** (my courses), **Search** (search course materials), **Saved** (bookmarks), **Progress** (my study stats), and **Settings**. The selected tab sits on a soft tinted capsule. Beside the pill, as a separate **round glass button**, is **ViVi**, the AI assistant, shown as a cute round robot face with a small antenna spark. Both use translucent background blur, a bright 1dp hairline edge, a soft shadow, and smooth spring animations.
>
> **Brand.** Indigo → violet gradient (#4F46E5 → #7C3AED), soft lavender background (#F6F5FB), white glass surfaces; a deep ink (#0D0F16) dark theme with lifted indigo. Semantic colours keep their meaning: **green = teacher-approved**, **amber = not covered / pending**, violet = general-knowledge AI answers. Rounded corners 24–32dp, generous spacing, 48dp+ touch targets, clear hierarchy, WCAG AA contrast, English and **Bangla (বাংলা)** text.
>
> **Screens.**
> 1. **Home (student):** a gradient hero with a greeting, plan badge and notification bell; floating course cards with a colour accent, course code, title, faculty, schedule and an "approved materials" chip.
> 2. **Course page:** course header and a list of approved materials (lecture, lab, assignment, quiz, notes) with type chips; a prominent "Ask a Question" button.
> 3. **Ask a Question (course):** answers only from that course's approved material, with numbered citations [1] [2] and tappable source cards; a "Not covered" state with a friendly "Ask ViVi instead" button.
> 4. **ViVi chat:** chat bubbles with ViVi's avatar; under each answer, a small label: "📚 From your courses: Lecture 3" (green, tappable) or "✨ General knowledge" (violet); no label for small talk.
> 5. **Document viewer:** real PDF pages, PowerPoint slides and Word pages on floating paper cards, with a download progress state and an "Open in…" action.
> 6. **Search, Saved, Progress:** glass search field with recent-search chips; saved-material cards with swipe-to-remove; stat tiles with count-up numbers and a grounding-rate meter.
> 7. **Settings:** profile card, change password, theme (light / dark / system), language, notifications, sign out.
> 8. **Sign in:** email and password, "Forgot password?", "Continue with Google", and an email-verification screen.
> 9. **Teacher:** dashboard of their courses with join codes and pending-review badges; approve or revoke materials (an animated lock chip); upload, edit and delete courses and materials; analytics.
>
> **Motion.** Micro-interactions on press (a slight scale dip), staggered card entrance on first load, the nav capsule sliding between tabs, chat bubbles popping in, and a shimmer while ViVi is thinking. Keep animations under 300ms and respect "remove animations".
>
> The result should feel premium, calm and trustworthy, like a modern SaaS app that Gen Z students enjoy opening, while staying clear about where every AI answer comes from.

## Keywords
Floating Navigation Bar · Liquid Glass · Glassmorphism · Frosted Glass · Background Blur · Floating Cards · Material 3 Expressive · Soft Shadows · Edge-to-Edge · Rounded Corners (24–32dp) · Depth & Layering · Micro-interactions · Spring Animations · Dynamic Gradients · Premium Mobile App · Modern SaaS Aesthetic · Accessible Contrast · Bilingual (English/Bangla)

## Reference
The navigation follows the reference image: a translucent white pill with icon-over-label tabs, the active tab on a soft tinted capsule, and a separate circular glass button on the right (ViVi here instead of search).
