package com.agy.core;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Polls the core's own LanguageServerService for the REAL cascade state:
 *   POST /exa.language_server_pb.LanguageServerService/GetAllCascadeTrajectories
 *   Connect protocol, Content-Type: application/proto, empty protobuf body.
 * (Wire format validated against the 2.33 core: HTTP 200, raw protobuf
 * response; request message has a single optional bool field.)
 *
 * Response: field 1 = map entries {1: key, 2: CascadeTrajectorySummary}:
 *   summary field 1 = title (string), 4 = trajectory id,
 *   5 = status (exa.cortex_pb.CascadeRunStatus: 1 IDLE, 2 RUNNING,
 *       3 CANCELING, 4 BUSY), 18 = has_active_children,
 *   21 = not_fully_idle, 29 = background_task_count,
 *   30 = has_running_subagents.
 * Field layout extracted from the core binary's embedded descriptors.
 */
public final class CascadeStatusMonitor {

    public enum Kind { ACTIVE, IDLE, ERROR }

    public static final class Result {
        public final Kind kind;
        public final String title;
        /** One-line view of every summary seen (title:status:age), for logs. */
        public final String debug;
        Result(Kind kind, String title, String debug) {
            this.kind = kind;
            this.title = title;
            this.debug = debug;
        }
    }

    private static final Result ERROR = new Result(Kind.ERROR, "", "");
    private static volatile int lastLoggedHttpCode = 0;

    private CascadeStatusMonitor() {}

    public static Result poll(Context context, int port, String csrfToken) {
        HttpsURLConnection conn = null;
        try {
            URL url = new URL("https://127.0.0.1:" + port
                    + "/exa.language_server_pb.LanguageServerService/GetAllCascadeTrajectories");
            conn = (HttpsURLConnection) url.openConnection();
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier((HostnameVerifier) (h, s) -> true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/proto");
            conn.setRequestProperty("Connect-Protocol-Version", "1");
            if (csrfToken != null && !csrfToken.isEmpty()) {
                conn.setRequestProperty("x-codeium-csrf-token", csrfToken);
            }
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(0);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            if (code != 200) {
                if (code != lastLoggedHttpCode) {
                    lastLoggedHttpCode = code;
                    com.agy.log.TaskLiveLog.append(context,
                            "monitor http " + code);
                }
                return ERROR;
            }
            lastLoggedHttpCode = 200;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 1024 * 1024) {
                    break;
                }
                bos.write(buf, 0, n);
            }
            in.close();
            return parse(bos.toByteArray());
        } catch (Exception e) {
            return ERROR;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** One parsed protobuf field. */
    private static final class F {
        int num;
        int wt;
        long var;
        byte[] bytes;
    }

    private static List<F> walk(byte[] b) {
        List<F> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            long tag = 0;
            int shift = 0;
            while (true) {
                if (i >= b.length) return out;
                int x = b[i++] & 0xff;
                tag |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) break;
                shift += 7;
                if (shift > 63) return out;
            }
            F f = new F();
            f.num = (int) (tag >> 3);
            f.wt = (int) (tag & 7);
            if (f.wt == 0) {
                long v = 0;
                shift = 0;
                while (true) {
                    if (i >= b.length) return out;
                    int x = b[i++] & 0xff;
                    v |= (long) (x & 0x7f) << shift;
                    if ((x & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 63) return out;
                }
                f.var = v;
            } else if (f.wt == 2) {
                long ln = 0;
                shift = 0;
                while (true) {
                    if (i >= b.length) return out;
                    int x = b[i++] & 0xff;
                    ln |= (long) (x & 0x7f) << shift;
                    if ((x & 0x80) == 0) break;
                    shift += 7;
                    if (shift > 63) return out;
                }
                if (ln < 0 || i + ln > b.length) return out;
                f.bytes = new byte[(int) ln];
                System.arraycopy(b, i, f.bytes, 0, (int) ln);
                i += (int) ln;
            } else if (f.wt == 5) {
                if (i + 4 > b.length) return out;
                i += 4;
            } else if (f.wt == 1) {
                if (i + 8 > b.length) return out;
                i += 8;
            } else {
                return out;
            }
            out.add(f);
        }
        return out;
    }

    private static Result parse(byte[] resp) {
        String activeTitle = "";
        boolean anyActive = false;
        long nowSec = System.currentTimeMillis() / 1000L;
        StringBuilder debug = new StringBuilder();
        int count = 0;
        for (F entry : walk(resp)) {
            if (entry.num != 1 || entry.wt != 2 || entry.bytes == null) {
                continue;
            }
            byte[] summaryBytes = null;
            for (F kv : walk(entry.bytes)) {
                if (kv.num == 2 && kv.wt == 2) {
                    summaryBytes = kv.bytes;
                }
            }
            if (summaryBytes == null) {
                continue;
            }
            count++;
            String title = "";
            long status = 0;
            long modifiedSec = 0;
            boolean aux = false;
            for (F sf : walk(summaryBytes)) {
                switch (sf.num) {
                    case 1:
                        if (sf.wt == 2 && sf.bytes != null) {
                            title = new String(sf.bytes,
                                    java.nio.charset.StandardCharsets.UTF_8);
                        }
                        break;
                    case 3: // last_modified_time (google.protobuf.Timestamp)
                        if (sf.wt == 2 && sf.bytes != null) {
                            for (F tf : walk(sf.bytes)) {
                                if (tf.num == 1 && tf.wt == 0) {
                                    modifiedSec = tf.var;
                                }
                            }
                        }
                        break;
                    case 5:
                        status = sf.var;
                        break;
                    case 18: // has_active_children
                    case 21: // not_fully_idle
                    case 30: // has_running_subagents
                        if (sf.var != 0) {
                            aux = true;
                        }
                        break;
                    case 29: // background_task_count
                        if (sf.var > 0) {
                            aux = true;
                        }
                        break;
                    default:
                        break;
                }
            }
            long ageSec = modifiedSec > 0 ? Math.max(0L, nowSec - modifiedSec) : 0L;
            // The status field is authoritative: RUNNING / CANCELING / BUSY
            // means the cascade is alive, however old the summary looks.
            // The auxiliary flags are only hints: on an IDLE summary they
            // can be left stuck by a crashed subagent or background task,
            // so they count only while the summary is fresh (10 min) -
            // a genuinely busy conversation keeps its summary updated.
            boolean active = (status == 2 || status == 3 || status == 4)
                    || (aux && ageSec <= 600L);
            if (debug.length() > 0) {
                debug.append(' ');
            }
            debug.append('[')
                    .append(title.length() > 18 ? title.substring(0, 18) : title)
                    .append(":st=").append(status)
                    .append(":age=").append(ageSec).append('s')
                    .append(aux ? ":aux" : "")
                    .append(active ? ":ACTIVE" : "")
                    .append(']');
            if (active) {
                anyActive = true;
                // Prefer the first active with a real title; a RUNNING one
                // wins over a merely not-idle one when titles are missing.
                if (!title.isEmpty() && (activeTitle.isEmpty() || status == 2)) {
                    activeTitle = title;
                }
            }
        }
        String dbg = "n=" + count + " " + debug;
        if (anyActive) {
            return new Result(Kind.ACTIVE, activeTitle, dbg);
        }
        return new Result(Kind.IDLE, "", dbg);
    }
}
