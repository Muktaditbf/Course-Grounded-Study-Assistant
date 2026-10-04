package com.seu.studyassistant.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The small subset of Markdown that AI replies use, rendered for a TextView: **bold**,
 * *italic*, "- " / "* " bullets and "#" headings. Everything else is left as plain text,
 * so a reply never shows stray asterisks or hashes.
 */
final class MarkdownLite {

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])");

    private MarkdownLite() {}

    static CharSequence render(String raw) {
        if (raw == null) return "";
        StringBuilder lines = new StringBuilder();
        for (String line : raw.split("\n", -1)) {
            String t = line;
            String trimmed = t.trim();
            if (trimmed.startsWith("#")) {
                t = trimmed.replaceFirst("^#+\\s*", "**") + "**";    // heading -> bold line
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                int indent = t.indexOf(trimmed);
                t = (indent > 0 ? "    " : "") + "• " + trimmed.substring(2);
            }
            if (lines.length() > 0) lines.append('\n');
            lines.append(t);
        }
        SpannableStringBuilder out = new SpannableStringBuilder(lines.toString());
        apply(out, BOLD, Typeface.BOLD);
        apply(out, ITALIC, Typeface.ITALIC);
        return out;
    }

    /** Replaces each match with its inner text and styles it, working back to front. */
    private static void apply(SpannableStringBuilder sb, Pattern p, int style) {
        Matcher m = p.matcher(sb.toString());
        java.util.List<int[]> hits = new java.util.ArrayList<>();
        while (m.find()) {
            int g = m.group(1) != null ? 1 : 2;
            hits.add(new int[]{m.start(), m.end(), m.start(g), m.end(g)});
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            int[] h = hits.get(i);
            CharSequence inner = sb.subSequence(h[2], h[3]);
            sb.replace(h[0], h[1], inner);
            sb.setSpan(new StyleSpan(style), h[0], h[0] + inner.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
}
