package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/**
 * Compile-time shim for FongMi's private {@code LibassFontFile}.
 *
 * <p>See {@link LibassConfiguration} for why this shim exists and how to remove it.
 *
 * <p>Unlike the other shims in this package, {@link #getFamilyName(File)} is a <b>real</b>
 * implementation rather than a stub. {@code com.fongmi.android.tv.player.subtitle.ExternalFont}
 * calls it to label each imported font in the subtitle font picker, and that picker is shared with
 * the mpv engine — so the feature has to keep working without libass. It parses the OpenType
 * {@code name} table directly.
 *
 * <p>Supported containers: TrueType ({@code 0x00010000}), OpenType/CFF ({@code OTTO}),
 * {@code true}/{@code typ1}, and TrueType Collections ({@code ttcf}, first font).
 */
public final class LibassFontFile {

    private static final int TAG_TTCF = 0x74746366; // 'ttcf'
    private static final int TAG_OTTO = 0x4F54544F; // 'OTTO'
    private static final int TAG_TRUE = 0x74727565; // 'true'
    private static final int TAG_TYP1 = 0x74797031; // 'typ1'
    private static final int TAG_NAME = 0x6E616D65; // 'name'
    private static final int SFNT_VERSION_TRUETYPE = 0x00010000;

    private static final int NAME_ID_FAMILY = 1;
    private static final int NAME_ID_TYPOGRAPHIC_FAMILY = 16;

    private static final int PLATFORM_UNICODE = 0;
    private static final int PLATFORM_MACINTOSH = 1;
    private static final int PLATFORM_WINDOWS = 3;

    private static final int LANGUAGE_EN_US = 0x0409;

    private static final int MAX_TABLES = 4096;
    private static final int MAX_NAME_RECORDS = 4096;
    private static final int MAX_NAME_BYTES = 1024;

    private LibassFontFile() {
    }

    /**
     * Reads the font family name (OpenType {@code name} table, name ID 1 — falling back to the
     * typographic family, name ID 16).
     *
     * @return the family name, or {@code null} when the font declares none.
     * @throws IOException when the file cannot be read or is not a supported font container.
     */
    @Nullable
    public static String getFamilyName(File file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long sfnt = findSfntOffset(raf);
            long nameTable = findNameTable(raf, sfnt);
            return nameTable < 0 ? null : readFamilyName(raf, nameTable);
        }
    }

    /** Resolves the offset of the first font's table directory inside a TTC, else {@code 0}. */
    private static long findSfntOffset(RandomAccessFile raf) throws IOException {
        if (raf.length() < 12) throw new IOException("Font file is too small");
        int version = readInt(raf, 0);
        if (version == TAG_TTCF) {
            long fontCount = readUnsignedInt(raf, 8);
            if (fontCount < 1) throw new IOException("Font collection is empty");
            long offset = readUnsignedInt(raf, 12);
            if (offset + 12 > raf.length()) throw new IOException("Truncated font collection");
            return offset;
        }
        boolean supported = version == SFNT_VERSION_TRUETYPE || version == TAG_OTTO || version == TAG_TRUE || version == TAG_TYP1;
        if (!supported) throw new IOException("Unsupported font file");
        return 0;
    }

    /** Returns the absolute offset of the {@code name} table, or {@code -1} when absent. */
    private static long findNameTable(RandomAccessFile raf, long sfnt) throws IOException {
        int numTables = readUnsignedShort(raf, sfnt + 4);
        if (numTables <= 0 || numTables > MAX_TABLES) throw new IOException("Invalid table count");
        for (int i = 0; i < numTables; i++) {
            long record = sfnt + 12 + (long) i * 16;
            if (record + 16 > raf.length()) throw new IOException("Truncated table directory");
            if (readInt(raf, record) == TAG_NAME) return readUnsignedInt(raf, record + 8);
        }
        return -1;
    }

    /** Picks the best-scoring family-name record from the {@code name} table. */
    @Nullable
    private static String readFamilyName(RandomAccessFile raf, long nameTable) throws IOException {
        if (nameTable + 6 > raf.length()) return null;
        int count = readUnsignedShort(raf, nameTable + 2);
        long stringOffset = nameTable + readUnsignedShort(raf, nameTable + 4);
        if (count <= 0 || count > MAX_NAME_RECORDS) return null;
        String best = null;
        int bestScore = -1;
        for (int i = 0; i < count; i++) {
            long record = nameTable + 6 + (long) i * 12;
            if (record + 12 > raf.length()) break;
            int platform = readUnsignedShort(raf, record);
            int language = readUnsignedShort(raf, record + 4);
            int nameId = readUnsignedShort(raf, record + 6);
            int length = readUnsignedShort(raf, record + 8);
            int offset = readUnsignedShort(raf, record + 10);
            int score = score(platform, language, nameId);
            if (score <= bestScore || length <= 0 || length > MAX_NAME_BYTES) continue;
            long start = stringOffset + offset;
            if (start < 0 || start + length > raf.length()) continue;
            String value = decode(raf, start, length, platform);
            if (value == null) continue;
            best = value;
            bestScore = score;
        }
        return best;
    }

    /** Higher is better; {@code -1} rejects the record. */
    private static int score(int platform, int language, int nameId) {
        if (nameId != NAME_ID_FAMILY && nameId != NAME_ID_TYPOGRAPHIC_FAMILY) return -1;
        int score = nameId == NAME_ID_FAMILY ? 100 : 50;
        if (platform == PLATFORM_WINDOWS) score += 30;
        else if (platform == PLATFORM_UNICODE) score += 25;
        else if (platform == PLATFORM_MACINTOSH) score += 10;
        else return -1;
        if (language == LANGUAGE_EN_US) score += 15;
        return score;
    }

    @Nullable
    private static String decode(RandomAccessFile raf, long offset, int length, int platform) throws IOException {
        raf.seek(offset);
        byte[] bytes = new byte[length];
        raf.readFully(bytes);
        String value = platform == PLATFORM_MACINTOSH ? new String(bytes, StandardCharsets.ISO_8859_1) : new String(bytes, StandardCharsets.UTF_16BE);
        value = value.replace("\u0000", "").trim();
        return value.isEmpty() ? null : value;
    }

    private static int readInt(RandomAccessFile raf, long offset) throws IOException {
        raf.seek(offset);
        return raf.readInt();
    }

    private static int readUnsignedShort(RandomAccessFile raf, long offset) throws IOException {
        raf.seek(offset);
        return raf.readUnsignedShort();
    }

    private static long readUnsignedInt(RandomAccessFile raf, long offset) throws IOException {
        raf.seek(offset);
        return raf.readInt() & 0xFFFFFFFFL;
    }
}
