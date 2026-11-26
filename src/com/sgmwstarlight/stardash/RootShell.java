package com.sgmwstarlight.stardash;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Runs a shell command as root through the head unit's own adbd on 127.0.0.1:5557 (ro.adb.secure=0, so
 * no key is needed). Used only for what an app cannot do itself: formatting the USB stick, and writing to
 * it if the app's own access is refused. Speaks the plain adb wire protocol.
 */
final class RootShell {
    private static final int CNXN = 0x4e584e43, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43,
            WRTE = 0x45545257, AUTH = 0x48545541;
    private static final int PORT = 5557;

    /** Output of a command and its exit code (-1 if it could not run). */
    static final class Result {
        final int code;
        final String out;

        Result(int code, String out) {
            this.code = code;
            this.out = out;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private RootShell() {
    }

    static Result run(String command, int timeoutSec) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(timeoutSec * 1000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
            int[] h = new int[6];
            byte[] data = read(in, h);
            if (h[0] == AUTH) return new Result(-1, "adb 需要授权，无法获取 root 权限");
            if (h[0] != CNXN) return new Result(-1, "adb 握手失败");
            String full = command + " 2>&1; echo \"__RC=$?\"";
            send(out, OPEN, 1, 0, ("shell:" + full + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                data = read(in, h);
                if (h[0] == OKAY) {
                    remote = h[1];
                } else if (h[0] == WRTE) {
                    remote = h[1];
                    text.write(data);
                    send(out, OKAY, 1, remote, new byte[0]);
                } else if (h[0] == CLSE) {
                    send(out, CLSE, 1, remote, new byte[0]);
                    break;
                }
            }
            String all = text.toString("UTF-8");
            int at = all.lastIndexOf("__RC=");
            if (at < 0) return new Result(-1, all.trim());
            int code;
            try {
                code = Integer.parseInt(all.substring(at + 5).trim());
            } catch (NumberFormatException e) {
                code = -1;
            }
            return new Result(code, all.substring(0, at).trim());
        } catch (IOException e) {
            return new Result(-1, "无法连接车机 adb：" + e.getMessage());
        }
    }

    /** Quotes a path or argument for the shell. */
    static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        b.put(data);
        out.write(b.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in, int[] h) throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 6; i++) h[i] = b.getInt();
        int len = h[3];
        if (len < 0 || len > (1 << 20)) throw new IOException("bad adb packet");
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }
}
