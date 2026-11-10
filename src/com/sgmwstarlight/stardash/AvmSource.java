package com.sgmwstarlight.stardash;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;
import java.io.EOFException;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only subscriber to the head unit's shared 360 mosaic (/data/system/avm_svr).
 * Sends only the connect and subscribe messages; never camera or power commands.
 */
final class AvmSource {
    static final int WIDTH = 2560, HEIGHT = 1920, SIZE = WIDTH * HEIGHT * 2;
    private static final String TAG = "StarDash";
    private final int clientId;
    private LocalSocket socket;
    private InputStream in;
    private final List<FileInputStream> owners = new ArrayList<>();
    private ByteBuffer[] maps;

    AvmSource(int clientId) {
        this.clientId = clientId;
    }

    boolean isOpen() {
        return socket != null;
    }

    void open() throws IOException {
        close();
        LocalSocket s = new LocalSocket();
        try {
            s.connect(new LocalSocketAddress("/data/system/avm_svr", LocalSocketAddress.Namespace.FILESYSTEM));
            s.setSoTimeout(4000);
            InputStream input = s.getInputStream();
            OutputStream output = s.getOutputStream();
            output.write(new byte[]{0x55, (byte) 0xaa, (byte) clientId, (byte) 0xfd});
            output.flush();
            ByteBuffer header = ByteBuffer.wrap(exact(input, 8)).order(ByteOrder.LITTLE_ENDIAN);
            FileDescriptor[] fds = s.getAncillaryFileDescriptors();
            header.getInt();
            int metaCount = header.getInt();
            if (metaCount < 3 || metaCount > 16 || fds == null || fds.length < 1 || fds.length > 16) {
                throw new IOException("bad metadata");
            }
            ByteBuffer meta = ByteBuffer.wrap(exact(input, metaCount * 4)).order(ByteOrder.LITTLE_ENDIAN);
            if (meta.getInt() != WIDTH || meta.getInt() != HEIGHT) throw new IOException("unexpected source size");
            ByteBuffer[] m = new ByteBuffer[fds.length];
            for (int i = 0; i < fds.length; i++) {
                FileInputStream owner = new FileInputStream(fds[i]);
                owners.add(owner);
                m[i] = owner.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, SIZE);
            }
            output.write(new byte[]{0x55, (byte) 0xaa, (byte) (clientId | 0xf0), (byte) 0xfd});
            output.flush();
            socket = s;
            in = input;
            maps = m;
            Log.i(TAG, "avm connected buffers=" + fds.length);
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
            closeOwners();
            throw e;
        }
    }

    /** Blocks until the next frame and returns its buffer, skipping any older pending notices. */
    ByteBuffer next() throws IOException {
        int index = -1;
        while (index < 0) {
            index = readNotice();
        }
        while (in.available() >= 7) {
            int newer = readNotice();
            if (newer >= 0) index = newer;
        }
        return maps[index].duplicate();
    }

    private int readNotice() throws IOException {
        byte[] p = exact(in, 7);
        if ((p[0] & 255) != 0x55 || (p[1] & 255) != 0xaa || p[2] != 3 || (p[6] & 255) != 0xfd) {
            throw new IOException("bad notice");
        }
        int channel = p[3] & 255, index = p[4] & 255;
        if (channel != 0 || index >= maps.length) return -1;
        return index;
    }

    void close() {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        socket = null;
        in = null;
        maps = null;
        closeOwners();
    }

    private void closeOwners() {
        for (FileInputStream o : owners) {
            try {
                o.close();
            } catch (IOException ignored) {
            }
        }
        owners.clear();
    }

    private static byte[] exact(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int p = 0;
        while (p < n) {
            int r = in.read(b, p, n - p);
            if (r < 0) throw new EOFException();
            p += r;
        }
        return b;
    }
}
