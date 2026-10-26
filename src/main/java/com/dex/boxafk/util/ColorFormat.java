package com.dex.boxafk.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites every supported colour syntax into {@code &#RRGGBB}, the one form both Adventure's legacy parser and
 * DecentHolograms read:
 * <ul>
 *     <li>{@code <gradient:#FA912E:#EFF665>text</gradient>} (two or more stops; {@code &l} etc. inside are kept)</li>
 *     <li>{@code <#RRGGBB>}, {@code {#RRGGBB}}, {@code &#RRGGBB}</li>
 *     <li>{@code &x&R&R&G&G&B&B} / {@code §x§R§R§G§G§B§B} (Spigot / EssentialsX style)</li>
 * </ul>
 * Plain {@code &a}, {@code &l}, ... are left as they are.
 */
public final class ColorFormat {

    private static final Pattern GRADIENT = Pattern.compile(
            "<gradient:(#[0-9a-fA-F]{6}(?::#[0-9a-fA-F]{6})+)>(.*?)</gradient>", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_TAG = Pattern.compile("<#([0-9a-fA-F]{6})>|\\{#([0-9a-fA-F]{6})}");
    private static final Pattern SPIGOT_HEX = Pattern.compile(
            "[&§][xX][&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])[&§]([0-9a-fA-F])");

    private ColorFormat() {
    }

    public static String normalize(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text;
        // Single colours first, so codes inside a gradient are already in the form it skips.
        if (s.indexOf('x') >= 0 || s.indexOf('X') >= 0) {
            s = SPIGOT_HEX.matcher(s).replaceAll("&#$1$2$3$4$5$6");
        }
        if (s.indexOf('<') >= 0 || s.indexOf('{') >= 0) {
            s = HEX_TAG.matcher(s).replaceAll(m -> "&#" + (m.group(1) != null ? m.group(1) : m.group(2)));
            s = replaceGradients(s);
        }
        return s;
    }

    private static String replaceGradients(String s) {
        Matcher m = GRADIENT.matcher(s);
        if (!m.find()) return s;
        StringBuilder out = new StringBuilder(s.length() * 4);
        do {
            String[] stops = m.group(1).split(":");
            int[] rgb = new int[stops.length];
            for (int i = 0; i < stops.length; i++) {
                rgb[i] = Integer.parseInt(stops[i].substring(1), 16);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(gradient(rgb, m.group(2))));
        } while (m.find());
        m.appendTail(out);
        return out.toString();
    }

    /** One {@code &#RRGGBB} per visible character; formats (&l, &o, ...) are re-applied after every colour. */
    private static String gradient(int[] stops, String inner) {
        int visible = 0;
        for (int i = 0; i < inner.length(); ) {
            int skip = codeLength(inner, i);
            if (skip > 0) {
                i += skip;
            } else {
                visible++;
                i += Character.charCount(inner.codePointAt(i));
            }
        }
        StringBuilder out = new StringBuilder(inner.length() * 12);
        StringBuilder formats = new StringBuilder();
        int index = 0;
        for (int i = 0; i < inner.length(); ) {
            int skip = codeLength(inner, i);
            if (skip > 0) {
                char code = Character.toLowerCase(inner.charAt(i + 1));
                if (code == 'r') {
                    formats.setLength(0);
                } else if (code >= 'k' && code <= 'o' && formats.indexOf("&" + code) < 0) {
                    formats.append('&').append(code);
                }
                // Colours inside a gradient are ignored: the gradient decides the colour.
                i += skip;
                continue;
            }
            int cp = inner.codePointAt(i);
            double t = visible <= 1 ? 0 : (double) index / (visible - 1);
            out.append("&#").append(hex(interpolate(stops, t))).append(formats).appendCodePoint(cp);
            index++;
            i += Character.charCount(cp);
        }
        return out.toString();
    }

    /** Length of the colour/format code starting at {@code i} ({@code &a}: 2, {@code &#RRGGBB}: 8), else 0. */
    private static int codeLength(String s, int i) {
        char c = s.charAt(i);
        if ((c != '&' && c != '§') || i + 1 >= s.length()) return 0;
        char next = Character.toLowerCase(s.charAt(i + 1));
        if (next == '#' && isHex(s, i + 2, 6)) return 8;
        if ((next >= '0' && next <= '9') || (next >= 'a' && next <= 'f') || (next >= 'k' && next <= 'o') || next == 'r') {
            return 2;
        }
        return 0;
    }

    private static boolean isHex(String s, int from, int count) {
        if (from + count > s.length()) return false;
        for (int i = from; i < from + count; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        return true;
    }

    private static int interpolate(int[] stops, double t) {
        double scaled = t * (stops.length - 1);
        int seg = Math.min((int) scaled, stops.length - 2);
        double local = scaled - seg;
        int a = stops[seg];
        int b = stops[seg + 1];
        int r = (int) Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * local);
        int g = (int) Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * local);
        int bl = (int) Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * local);
        return (r << 16) | (g << 8) | bl;
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "%06X", rgb);
    }
}
