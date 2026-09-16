package io.github.lcebot.clipsync;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Service state shared with the UI across processes (the service lives in ":sync"):
 * files/status.json, rewritten by the service on every change and refreshed by the pinger,
 * read by MainActivity every second.
 */
public final class Status {
    private Status() {}

    public static final class Snapshot {
        public final String state;      // stopped | connecting | connected | disconnected | no network | idle
        public final String detail;     // free text for non-connected states (e.g. "retry in 3 s")
        public final String via;        // ddns | mdns (connected only)
        public final boolean lan;
        public final String host;       // DDNS name or mDNS service name
        public final String addr;       // ip:port actually used
        public final long ts;           // wall-clock ms of the last write
        public final boolean suspended; // the pinger caught the process being frozen at least once

        Snapshot(String state, String detail, String via, boolean lan, String host, String addr, long ts, boolean suspended) {
            this.state = state; this.detail = detail; this.via = via; this.lan = lan;
            this.host = host; this.addr = addr; this.ts = ts; this.suspended = suspended;
        }

        /** The service process wrote recently and is not stopped. */
        public boolean alive() {
            return !"stopped".equals(state) && System.currentTimeMillis() - ts < 120_000;
        }
    }

    private static File file(Context ctx) {
        return new File(ctx.getApplicationContext().getFilesDir(), "status.json");
    }

    public static void write(Context ctx, String state, String detail, String via, boolean lan, String host, String addr, boolean suspended) {
        try {
            String s = new JSONObject().put("state", state).put("detail", detail == null ? JSONObject.NULL : detail)
                    .put("via", via == null ? JSONObject.NULL : via).put("lan", lan)
                    .put("host", host == null ? JSONObject.NULL : host).put("addr", addr == null ? JSONObject.NULL : addr)
                    .put("ts", System.currentTimeMillis()).put("suspended", suspended).toString();
            File f = file(ctx), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        } catch (Exception ignored) {
        }
    }

    public static Snapshot read(Context ctx) {
        try (InputStream in = new FileInputStream(file(ctx))) {
            JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return new Snapshot(o.optString("state", "stopped"), o.isNull("detail") ? null : o.optString("detail"),
                    o.isNull("via") ? null : o.optString("via"), o.optBoolean("lan"),
                    o.isNull("host") ? null : o.optString("host"), o.isNull("addr") ? null : o.optString("addr"),
                    o.optLong("ts", 0), o.optBoolean("suspended"));
        } catch (Exception e) {
            return new Snapshot("stopped", null, null, false, null, null, 0, false);
        }
    }
}
