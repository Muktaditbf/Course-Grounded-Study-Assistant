package com.seu.studyassistant.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tiny read-only XML tree for Office Open XML parts.
 *
 * Element and attribute names are stored without their namespace prefix ("p:sp" becomes "sp",
 * "r:embed" becomes "embed"). OOXML prefixes are conventional rather than fixed, and the
 * renderers only ever need the local name, so dropping them keeps every lookup simple.
 */
final class XNode {

    final String name;
    final Map<String, String> attrs;
    final List<XNode> children = new ArrayList<>();
    private StringBuilder text;

    private XNode(String name, Map<String, String> attrs) {
        this.name = name;
        this.attrs = attrs;
    }

    static XNode parse(InputStream in) throws Exception {
        XmlPullParser p = Xml.newPullParser();
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        p.setInput(in, "UTF-8");

        XNode root = new XNode("#root", Collections.<String, String>emptyMap());
        List<XNode> stack = new ArrayList<>();
        stack.add(root);

        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                Map<String, String> a = p.getAttributeCount() == 0
                        ? Collections.<String, String>emptyMap()
                        : new HashMap<String, String>(p.getAttributeCount() * 2);
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    // Both forms are kept: the local name for ordinary lookups, and the full
                    // name for the rare clash (presentation.xml's sldId has id AND r:id).
                    String q = p.getAttributeName(i);
                    a.put(q, p.getAttributeValue(i));
                    String l = local(q);
                    if (!a.containsKey(l)) a.put(l, p.getAttributeValue(i));
                }
                XNode n = new XNode(local(p.getName()), a);
                stack.get(stack.size() - 1).children.add(n);
                stack.add(n);
            } else if (ev == XmlPullParser.END_TAG) {
                stack.remove(stack.size() - 1);
            } else if (ev == XmlPullParser.TEXT) {
                XNode top = stack.get(stack.size() - 1);
                if (top.text == null) top.text = new StringBuilder();
                top.text.append(p.getText());
            }
        }
        return root.children.isEmpty() ? root : root.children.get(0);
    }

    private static String local(String qname) {
        int i = qname.indexOf(':');
        return i < 0 ? qname : qname.substring(i + 1);
    }

    String text() { return text == null ? "" : text.toString(); }

    String attr(String name) { return attrs.get(name); }

    String attr(String name, String fallback) {
        String v = attrs.get(name);
        return v == null ? fallback : v;
    }

    long attrLong(String name, long fallback) {
        String v = attrs.get(name);
        if (v == null) return fallback;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return fallback; }
    }

    /** First direct child with this name, or null. */
    XNode child(String name) {
        for (XNode c : children) if (c.name.equals(name)) return c;
        return null;
    }

    /** Follows a path of direct children: path("spPr", "xfrm", "off"). */
    XNode path(String... names) {
        XNode n = this;
        for (String s : names) {
            if (n == null) return null;
            n = n.child(s);
        }
        return n;
    }

    List<XNode> all(String name) {
        List<XNode> out = new ArrayList<>();
        for (XNode c : children) if (c.name.equals(name)) out.add(c);
        return out;
    }

    /** First descendant with this name, depth first, or null. */
    XNode find(String name) {
        for (XNode c : children) {
            if (c.name.equals(name)) return c;
            XNode f = c.find(name);
            if (f != null) return f;
        }
        return null;
    }
}
