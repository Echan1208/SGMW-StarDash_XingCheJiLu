package com.sgmwstarlight.stardash;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain (non-fragmented) MP4 with one H.264 or H.265 track, so every player can seek in it.
 * While a file is open it is named *.mp4.tmp and an *.idx sidecar records each sample; if power is
 * lost, {@link #recover} rebuilds the index box from the sidecar and the footage up to the last
 * flushed sample is kept.
 */
final class Mp4Writer {
    static final String TMP = ".tmp", IDX = ".idx";
    private static final int TIMESCALE = 90000;
    private static final int IDX_MAGIC = 0x53444958; // "SDIX"

    final File target;
    private final File tmp, idx;
    private final boolean hevc;
    private final int width, height;
    private final byte[][] params; // VPS/SPS/PPS (HEVC) or SPS/PPS (AVC), without start codes
    private final FileOutputStream fileOut;
    private final BufferedOutputStream out;
    private final DataOutputStream idxOut;
    private final FileOutputStream idxFile;
    private long written; // bytes written to the file so far
    private final IntList sizes = new IntList(), syncs = new IntList();
    private final LongList pts = new LongList();
    private long lastSyncMs;

    Mp4Writer(File target, boolean hevc, int width, int height, byte[] csd) throws IOException {
        this.target = target;
        this.tmp = new File(target.getPath() + TMP);
        this.idx = new File(target.getPath() + IDX);
        this.hevc = hevc;
        this.width = width;
        this.height = height;
        this.params = splitAnnexB(csd, 0, csd.length).toArray(new byte[0][]);
        fileOut = new FileOutputStream(tmp);
        out = new BufferedOutputStream(fileOut, 512 * 1024);
        byte[] head = header(hevc);
        out.write(head);
        written = head.length;
        idxFile = new FileOutputStream(idx);
        idxOut = new DataOutputStream(new BufferedOutputStream(idxFile, 16 * 1024));
        idxOut.writeInt(IDX_MAGIC);
        idxOut.writeBoolean(hevc);
        idxOut.writeInt(width);
        idxOut.writeInt(height);
        idxOut.writeInt(csd.length);
        idxOut.write(csd);
        idxOut.flush();
        lastSyncMs = System.currentTimeMillis();
    }

    int sampleCount() {
        return sizes.size;
    }

    long bytes() {
        return written;
    }

    /** Writes one access unit given as Annex-B (start codes). Parameter sets inside it are dropped. */
    void writeSample(byte[] data, int length, long ptsUs, boolean key) throws IOException {
        int size = 0;
        int start = -1, i = 0;
        // Find NAL units and write each as 4-byte length + payload.
        List<int[]> nals = nalRanges(data, length);
        for (int[] r : nals) {
            if (isParameterSet(data[r[0]])) continue;
            int len = r[1] - r[0];
            out.write(len >>> 24);
            out.write(len >>> 16);
            out.write(len >>> 8);
            out.write(len);
            out.write(data, r[0], len);
            size += 4 + len;
        }
        if (size == 0) return;
        written += size;
        sizes.add(size);
        pts.add(ptsUs);
        if (key) syncs.add(sizes.size);
        idxOut.writeInt(size);
        idxOut.writeLong(ptsUs);
        idxOut.writeBoolean(key);
        long now = System.currentTimeMillis();
        if (now - lastSyncMs >= 2000) {
            // Data first, then index, so the index never points past real data after a power cut.
            out.flush();
            fileOut.getFD().sync();
            idxOut.flush();
            idxFile.getFD().sync();
            lastSyncMs = now;
        }
    }

    /** Writes the index box, renames to the final name and removes the sidecar. */
    void close() throws IOException {
        out.flush();
        idxOut.flush();
        if (sizes.size == 0) {
            out.close();
            idxOut.close();
            tmp.delete();
            idx.delete();
            return;
        }
        byte[] moov = moov(hevc, width, height, params, sizes, pts, syncs);
        out.write(moov);
        out.flush();
        fileOut.getFD().sync();
        out.close();
        patchMdatSize(tmp, written);
        idxOut.close();
        if (!tmp.renameTo(target)) throw new IOException("rename failed " + tmp);
        idx.delete();
    }

    /** Abandons the file without finalizing (used only when nothing usable was written). */
    void discard() {
        try {
            out.close();
        } catch (IOException ignored) {
        }
        try {
            idxOut.close();
        } catch (IOException ignored) {
        }
        tmp.delete();
        idx.delete();
    }

    // ---- Recovery ----

    /** Rebuilds a file left as *.mp4.tmp + *.mp4.idx. Returns the finished file or null. */
    static File recover(File tmp) {
        String path = tmp.getPath();
        if (!path.endsWith(TMP)) return null;
        File target = new File(path.substring(0, path.length() - TMP.length()));
        File idx = new File(target.getPath() + IDX);
        try {
            if (!idx.exists()) {
                tmp.delete();
                return null;
            }
            boolean hevc;
            int width, height;
            byte[] csd;
            IntList sizes = new IntList(), syncs = new IntList();
            LongList pts = new LongList();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(idx)))) {
                if (in.readInt() != IDX_MAGIC) throw new IOException("bad idx");
                hevc = in.readBoolean();
                width = in.readInt();
                height = in.readInt();
                csd = new byte[in.readInt()];
                in.readFully(csd);
                long dataEnd = tmp.length();
                long offset = header(hevc).length;
                try {
                    while (true) {
                        int size = in.readInt();
                        long p = in.readLong();
                        boolean key = in.readBoolean();
                        if (offset + size > dataEnd) break;
                        offset += size;
                        sizes.add(size);
                        pts.add(p);
                        if (key) syncs.add(sizes.size);
                    }
                } catch (EOFException ignored) {
                }
                if (sizes.size < 2 || syncs.size == 0) {
                    tmp.delete();
                    idx.delete();
                    return null;
                }
                byte[][] params = splitAnnexB(csd, 0, csd.length).toArray(new byte[0][]);
                try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                    raf.setLength(offset);
                    raf.seek(offset);
                    raf.write(moov(hevc, width, height, params, sizes, pts, syncs));
                    raf.getFD().sync();
                }
                patchMdatSize(tmp, offset);
            }
            if (!tmp.renameTo(target)) return null;
            idx.delete();
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    // ---- Box building ----

    private static byte[] header(boolean hevc) {
        Box b = new Box();
        b.start("ftyp");
        b.str("isom");
        b.u32(0x200);
        b.str("isom");
        b.str("iso2");
        b.str(hevc ? "hvc1" : "avc1");
        b.str("mp41");
        b.end();
        // mdat with a 64-bit size, patched when the file is finished.
        b.u32(1);
        b.str("mdat");
        b.u64(0);
        return b.bytes();
    }

    private static void patchMdatSize(File file, long dataEnd) throws IOException {
        int mdatStart = header(false).length - 16;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(mdatStart + 8);
            raf.writeLong(dataEnd - mdatStart);
            raf.getFD().sync();
        }
    }

    private static byte[] moov(boolean hevc, int width, int height, byte[][] params, IntList sizes, LongList pts, IntList syncs) {
        int n = sizes.size;
        long[] durations = new long[n];
        for (int i = 0; i < n - 1; i++) {
            durations[i] = Math.max(1, (pts.get(i + 1) - pts.get(i)) * TIMESCALE / 1_000_000);
        }
        durations[n - 1] = n > 1 ? durations[n - 2] : TIMESCALE / 25;
        long total = 0;
        for (long d : durations) total += d;
        long movieMs = total * 1000 / TIMESCALE;

        long offset = header(hevc).length;
        Box b = new Box();
        b.start("moov");
        b.full("mvhd", 0, 0);
        b.u32(0);
        b.u32(0);
        b.u32(1000);
        b.u32((int) movieMs);
        b.u32(0x00010000);
        b.u16(0x0100);
        b.zeros(10);
        matrix(b);
        b.zeros(24);
        b.u32(2);
        b.end();

        b.start("trak");
        b.full("tkhd", 0, 3);
        b.u32(0);
        b.u32(0);
        b.u32(1);
        b.u32(0);
        b.u32((int) movieMs);
        b.zeros(8);
        b.u16(0);
        b.u16(0);
        b.u16(0);
        b.u16(0);
        matrix(b);
        b.u32(width << 16);
        b.u32(height << 16);
        b.end();

        b.start("mdia");
        b.full("mdhd", 0, 0);
        b.u32(0);
        b.u32(0);
        b.u32(TIMESCALE);
        b.u32((int) total);
        b.u16(0x55c4);
        b.u16(0);
        b.end();
        b.full("hdlr", 0, 0);
        b.u32(0);
        b.str("vide");
        b.zeros(12);
        b.raw("VideoHandler\0".getBytes(StandardCharsets.US_ASCII));
        b.end();

        b.start("minf");
        b.full("vmhd", 0, 1);
        b.zeros(8);
        b.end();
        b.start("dinf");
        b.full("dref", 0, 0);
        b.u32(1);
        b.full("url ", 0, 1);
        b.end();
        b.end();
        b.end();

        b.start("stbl");
        b.full("stsd", 0, 0);
        b.u32(1);
        b.start(hevc ? "hvc1" : "avc1");
        b.zeros(6);
        b.u16(1);
        b.zeros(16);
        b.u16(width);
        b.u16(height);
        b.u32(0x00480000);
        b.u32(0x00480000);
        b.u32(0);
        b.u16(1);
        byte[] name = new byte[32];
        b.raw(name);
        b.u16(0x18);
        b.u16(0xffff);
        if (hevc) hvcC(b, params);
        else avcC(b, params);
        b.end();
        b.end();

        // stts: run-length durations
        List<long[]> runs = new ArrayList<>();
        for (long d : durations) {
            if (!runs.isEmpty() && runs.get(runs.size() - 1)[1] == d) runs.get(runs.size() - 1)[0]++;
            else runs.add(new long[]{1, d});
        }
        b.full("stts", 0, 0);
        b.u32(runs.size());
        for (long[] r : runs) {
            b.u32((int) r[0]);
            b.u32((int) r[1]);
        }
        b.end();

        b.full("stss", 0, 0);
        b.u32(syncs.size);
        for (int i = 0; i < syncs.size; i++) b.u32(syncs.get(i));
        b.end();

        b.full("stsz", 0, 0);
        b.u32(0);
        b.u32(n);
        for (int i = 0; i < n; i++) b.u32(sizes.get(i));
        b.end();

        b.full("stsc", 0, 0);
        b.u32(1);
        b.u32(1);
        b.u32(1);
        b.u32(1);
        b.end();

        b.full("co64", 0, 0);
        b.u32(n);
        for (int i = 0; i < n; i++) {
            b.u64(offset);
            offset += sizes.get(i);
        }
        b.end();

        b.end(); // stbl
        b.end(); // minf
        b.end(); // mdia
        b.end(); // trak
        b.end(); // moov
        return b.bytes();
    }

    private static void matrix(Box b) {
        int[] m = {0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000};
        for (int v : m) b.u32(v);
    }

    private static void avcC(Box b, byte[][] params) {
        byte[] sps = null, pps = null;
        for (byte[] p : params) {
            int type = p[0] & 0x1f;
            if (type == 7 && sps == null) sps = p;
            else if (type == 8 && pps == null) pps = p;
        }
        if (sps == null || pps == null) throw new IllegalStateException("missing SPS/PPS");
        b.start("avcC");
        b.u8(1);
        b.u8(sps[1] & 255);
        b.u8(sps[2] & 255);
        b.u8(sps[3] & 255);
        b.u8(0xff);
        b.u8(0xe1);
        b.u16(sps.length);
        b.raw(sps);
        b.u8(1);
        b.u16(pps.length);
        b.raw(pps);
        b.end();
    }

    private static void hvcC(Box b, byte[][] params) {
        byte[] vps = null, sps = null, pps = null;
        for (byte[] p : params) {
            int type = (p[0] >> 1) & 0x3f;
            if (type == 32 && vps == null) vps = p;
            else if (type == 33 && sps == null) sps = p;
            else if (type == 34 && pps == null) pps = p;
        }
        if (vps == null || sps == null || pps == null) throw new IllegalStateException("missing VPS/SPS/PPS");
        // profile_tier_level sits right after the 2-byte NAL header and one byte of SPS fields.
        byte[] rbsp = unescape(sps, 2);
        b.start("hvcC");
        b.u8(1);
        for (int i = 1; i <= 12; i++) b.u8(rbsp[i] & 255); // profile, compat flags, constraint flags, level
        b.u16(0xf000);
        b.u8(0xfc);
        b.u8(0xfc | 1); // 4:2:0
        b.u8(0xf8);
        b.u8(0xf8);
        b.u16(0);
        b.u8(0x0f); // 1 temporal layer, nested, 4-byte lengths
        b.u8(3);
        for (byte[] p : new byte[][]{vps, sps, pps}) {
            b.u8(0x80 | ((p[0] >> 1) & 0x3f));
            b.u16(1);
            b.u16(p.length);
            b.raw(p);
        }
        b.end();
    }

    private static byte[] unescape(byte[] nal, int from) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(nal.length);
        int zeros = 0;
        for (int i = from; i < nal.length; i++) {
            int v = nal[i] & 255;
            if (zeros >= 2 && v == 3) {
                zeros = 0;
                continue;
            }
            o.write(v);
            zeros = v == 0 ? zeros + 1 : 0;
        }
        return o.toByteArray();
    }

    private boolean isParameterSet(byte first) {
        if (hevc) {
            int type = (first >> 1) & 0x3f;
            return type == 32 || type == 33 || type == 34 || type == 35;
        }
        int type = first & 0x1f;
        return type == 7 || type == 8 || type == 9;
    }

    static List<int[]> nalRanges(byte[] d, int length) {
        List<int[]> out = new ArrayList<>();
        int i = 0, start = -1;
        while (i + 3 <= length) {
            if (d[i] == 0 && d[i + 1] == 0 && (d[i + 2] == 1 || (i + 3 < length && d[i + 2] == 0 && d[i + 3] == 1))) {
                int sc = d[i + 2] == 1 ? 3 : 4;
                if (start >= 0) {
                    int end = i;
                    while (end > start && d[end - 1] == 0) end--;
                    if (end > start) out.add(new int[]{start, end});
                }
                i += sc;
                start = i;
            } else {
                i++;
            }
        }
        if (start >= 0 && start < length) out.add(new int[]{start, length});
        if (start < 0 && length > 0) out.add(new int[]{0, length}); // no start codes: one raw NAL
        return out;
    }

    private static List<byte[]> splitAnnexB(byte[] d, int from, int to) {
        List<byte[]> nals = new ArrayList<>();
        for (int[] r : nalRanges(d, to)) {
            byte[] n = new byte[r[1] - r[0]];
            System.arraycopy(d, r[0], n, 0, n.length);
            nals.add(n);
        }
        return nals;
    }

    // ---- Small helpers ----

    private static final class Box {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream(64 * 1024);
        private final List<Integer> stack = new ArrayList<>();
        private byte[] cache;

        void start(String type) {
            stack.add(o.size());
            u32(0);
            str(type);
        }

        void full(String type, int version, int flags) {
            start(type);
            u32((version << 24) | flags);
        }

        void end() {
            int at = stack.remove(stack.size() - 1);
            byte[] all = o.toByteArray();
            int size = all.length - at;
            all[at] = (byte) (size >>> 24);
            all[at + 1] = (byte) (size >>> 16);
            all[at + 2] = (byte) (size >>> 8);
            all[at + 3] = (byte) size;
            o.reset();
            o.write(all, 0, all.length);
        }

        void u8(int v) {
            o.write(v);
        }

        void u16(int v) {
            o.write(v >>> 8);
            o.write(v);
        }

        void u32(int v) {
            o.write(v >>> 24);
            o.write(v >>> 16);
            o.write(v >>> 8);
            o.write(v);
        }

        void u64(long v) {
            u32((int) (v >>> 32));
            u32((int) v);
        }

        void str(String s) {
            raw(s.getBytes(StandardCharsets.US_ASCII));
        }

        void zeros(int n) {
            for (int i = 0; i < n; i++) o.write(0);
        }

        void raw(byte[] b) {
            o.write(b, 0, b.length);
        }

        byte[] bytes() {
            return o.toByteArray();
        }
    }

    static final class IntList {
        int[] a = new int[2048];
        int size;

        void add(int v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        int get(int i) {
            return a[i];
        }
    }

    static final class LongList {
        long[] a = new long[2048];
        int size;

        void add(long v) {
            if (size == a.length) a = java.util.Arrays.copyOf(a, size * 2);
            a[size++] = v;
        }

        long get(int i) {
            return a[i];
        }
    }
}
