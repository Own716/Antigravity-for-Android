package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.core.CoreEnvironment;
import com.agy.core.CoreRuntimeState;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Unpacks the bundled native runtime (assets/runtime) and web UI (assets/web)
 * into getFilesDir(), verifies the runtime manifest hashes (fail-closed), and
 * describes how to launch the patched core through the glibc loader.
 *
 * targetSdk 28 is required so the loader/core can be exec'd from the data dir.
 */
public final class RuntimeManager {

    public static final String CORE_VERSION = RuntimeConfig.CORE_VERSION;

    private final Context appContext;
    private final String csrfToken;
    private final RuntimePaths paths;
    private final NetworkMonitorHolder network;
    /** Guards against starting the background env install more than once. */
    private final AtomicBoolean envInstallStarted = new AtomicBoolean(false);

    public RuntimeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.csrfToken = UUID.randomUUID().toString();
        this.paths = new RuntimePaths(context);
        this.network = new NetworkMonitorHolder(context);
    }

    public Context appContext() {
        return appContext;
    }

    // The selected port is process-global, NOT per-instance: the core service
    // and the main activity each construct their own RuntimeManager, and if
    // both run the selection independently they can disagree (the activity's
    // probe raced the just-started core, judged it "foreign", and picked the
    // next candidate while the core served the base port - the UI then
    // fetched a dead port forever). One static selection, made once, shared
    // by every instance, is the single source of truth.
    private static volatile int httpsPort = -1;
    private static volatile int httpsPortIndex = 0;
    private static boolean staleCoresCleaned = false;

    /**
     * The core's HTTPS port. The configured port (45157) is shared with sibling
     * builds (e.g. Leostryx) whose core may already be running under another
     * UID: it cannot be killed from here, and its CSRF token differs, so using
     * the port anyway breaks startup ("address already in use" for our core,
     * "invalid CSRF token" for the UI). When the configured port is held by a
     * foreign listener, fall back to a nearby free port for this process.
     */
    public int getHttpsPort() {
        int p = httpsPort;
        if (p > 0) {
            return p;
        }
        synchronized (RuntimeManager.class) {
            if (httpsPort > 0) {
                return httpsPort;
            }
            httpsPort = selectHttpsPort();
            return httpsPort;
        }
    }

    private int[] portCandidates() {
        int base = RuntimeConfig.httpsPort(appContext);
        // Skip base+1 (dev-edition core port) and base+3/base+4 (gateway ports).
        return new int[]{base, base + 2, base + 5, base + 6, base + 7};
    }

    private int selectHttpsPort() {
        // Clear orphaned cores from a previous app process BEFORE probing:
        // a stale core holding the base port would otherwise push this
        // selection to a fallback port for no reason.
        if (!staleCoresCleaned) {
            staleCoresCleaned = true;
            killStaleCores();
        }
        int[] candidates = portCandidates();
        for (int i = 0; i < candidates.length; i++) {
            int port = candidates[i];
            if (isFree(port)) {
                httpsPortIndex = i;
                if (i != 0) {
                    Log.i("RuntimeManager", "https port " + candidates[0] + " busy; using " + port);
                }
                return port;
            }
            if (isOurCore(port)) {
                httpsPortIndex = i;
                return port;
            }
            Log.w("RuntimeManager", "https port " + port + " held by a foreign process");
        }
        httpsPortIndex = 0;
        return candidates[0];
    }

    /**
     * Called by the supervisor when the core died with "address already in
     * use": move to the next candidate port and return it. The one-shot probe
     * at startup can lose a race against a sibling app starting its own core
     * at the same moment, so selection must be retryable, not final.
     */
    public int advanceHttpsPort() {
      synchronized (RuntimeManager.class) {
        int[] candidates = portCandidates();
        for (int step = 1; step <= candidates.length; step++) {
            int idx = (httpsPortIndex + step) % candidates.length;
            int port = candidates[idx];
            if (isFree(port) || isOurCore(port)) {
                httpsPortIndex = idx;
                httpsPort = port;
                Log.i("RuntimeManager", "advanced https port to " + port);
                return port;
            }
        }
        return httpsPort > 0 ? httpsPort : candidates[0];
      }
    }

    /**
     * Kills core processes left over from a previous app process (an app
     * update or process death orphans the forked core, which keeps holding
     * the startup port). Same-UID processes are visible in /proc; anything
     * running the core binary out of our bin dir is a stale core of ours.
     */
    public void killStaleCores() {
        try {
            String binNeedle = getBinDir().getAbsolutePath();
            int self = android.os.Process.myPid();
            java.io.File[] dirs = new java.io.File("/proc").listFiles();
            if (dirs == null) {
                return;
            }
            for (java.io.File dir : dirs) {
                int pid;
                try {
                    pid = Integer.parseInt(dir.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == self) {
                    continue;
                }
                try {
                    byte[] raw = java.nio.file.Files.readAllBytes(
                            new java.io.File(dir, "cmdline").toPath());
                    String cmdline = new String(raw, "UTF-8");
                    if (cmdline.contains(binNeedle) || cmdline.contains("liblanguage_server")) {
                        Log.w("RuntimeManager", "killing stale core process pid=" + pid);
                        android.system.Os.kill(pid, android.system.OsConstants.SIGKILL);
                    }
                } catch (Exception ignored) {
                    // Process vanished or not readable; nothing to do.
                }
            }
        } catch (Exception e) {
            Log.w("RuntimeManager", "stale core cleanup failed", e);
        }
    }

    /** True when nothing is bound to the port, proven by binding it ourselves. */
    private static boolean isFree(int port) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(false);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** True when the listener on the port is a core serving this instance's CSRF token. */
    private boolean isOurCore(int port) {
        HttpsURLConnection conn = null;
        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());
            conn = (HttpsURLConnection) new URL("https://127.0.0.1:" + port + "/").openConnection();
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
            conn.setConnectTimeout(600);
            conn.setReadTimeout(600);
            conn.setUseCaches(false);
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while (sb.length() < 16384 && (n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, "UTF-8"));
                if (sb.indexOf(csrfToken) >= 0) {
                    return true;
                }
            }
            return sb.indexOf(csrfToken) >= 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public File getRuntimeDir() {
        return paths.getRuntimeDir();
    }

    public File getWebDir() {
        return paths.getWebDir();
    }

    public File getBinDir() {
        return paths.getBinDir();
    }

    public File getLibDir() {
        return paths.getLibDir();
    }

    public File getCertFile() {
        return paths.getCertFile();
    }

    public File getGeminiDir() {
        return paths.getGeminiDir();
    }

    public File getAppDataDir() {
        return paths.getAppDataDir();
    }

    public File getProjectsDir() {
        return paths.getProjectsDir();
    }

    /**
     * Phase 1: extract {@code assets/runtime} (the core + glibc) and the web UI,
     * verify the runtime manifest sha256 (fail-closed), and prepare DNS/onboarding.
     * Idempotent: a runtime whose marker is present and whose manifest still
     * verifies is reused.
     */
    public synchronized void installCore() {
        CoreInstaller.installCore(this);
    }

    /**
     * Phase 2: extract the bionic toolchain and regenerate {@code runtime/bin}
     * bridges on a daemon thread, in parallel with core startup. Best-effort.
     */
    public void installEnvInBackground() {
        EnvInstallScheduler.schedule(this, envInstallStarted);
    }

    /** Loader + flags + env required to boot the patched core. */
    public String[] coreCommand() {
        File bin = getBinDir();
        File lib = getLibDir();
        File loader = new File(bin, RuntimePaths.LOADER_NAME);
        File core = new File(bin, RuntimePaths.CORE_NAME);
        File lseCompat = new File(lib, "liblse_compat.so");
        if (!loader.isFile()) {
            throw new IllegalStateException("glibc loader missing: " + loader);
        }
        if (!core.isFile()) {
            throw new IllegalStateException("core binary missing: " + core);
        }

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(loader.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(lib.getAbsolutePath());
        if (lseCompat.isFile()) {
            cmd.add("--preload");
            cmd.add(lseCompat.getAbsolutePath());
        }
        cmd.add(core.getAbsolutePath());
        cmd.add("--standalone");
        cmd.add("--override_ide_name");
        cmd.add("antigravity");
        cmd.add("--subclient_type");
        cmd.add("hub");
        cmd.add("--override_ide_version");
        cmd.add(CORE_VERSION);
        cmd.add("--override_user_agent_name");
        cmd.add("antigravity");
        cmd.add("--https_server_port");
        cmd.add(String.valueOf(getHttpsPort()));
        cmd.add("--csrf_token");
        cmd.add(csrfToken);
        // Stable across builds: HOME/.gemini/<APP_DATA_DIR_NAME>.
        cmd.add("--app_data_dir");
        cmd.add(RuntimePaths.APP_DATA_DIR_NAME);
        cmd.add("--web_bundle_path");
        cmd.add(getWebDir().getAbsolutePath());
        cmd.add("--enable_sidecars");

        return cmd.toArray(new String[0]);
    }

    public void applyEnvironment(ProcessBuilder pb) {
        CoreEnvironment.apply(this, pb);
    }

    // ---- network config ---------------------------------------------------

    /** Start watching the default network; on changes the DNS config is rewritten. */
    public void startNetworkMonitor() {
        network.start();
    }

    /** Unregisters the process network callback when the core service stops. */
    public void stopNetworkMonitor() {
        network.unregister();
    }

    /**
     * Refresh DNS config in place. Rewriting resolv.conf is enough for a running
     * core: it uses GODEBUG=netdns=go and re-reads the file, so the process is
     * not restarted.
     */
    public synchronized void refreshNetwork() {
        writeNetworkConfig();
        if (CoreRuntimeState.serverUrl != null) {
            android.util.Log.d("RuntimeManager", "network changed; rewrote DNS config (core stays up)");
        } else {
            android.util.Log.d("RuntimeManager", "network changed; DNS config refreshed");
        }
    }

    public synchronized void writeNetworkConfig() {
        NetworkConfigWriter.write(appContext);
    }
}
