package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.view.KeyEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Built-in HTTPS and WebSocket server for the Nexus TV PWA remote control.
 * Serves the mobile web app on port 8765 and handles real-time remote commands.
 */
public final class RemoteServer {
    public static final int PORT = 8765;
    public static final String BUILD_ID = "nexus_" + System.currentTimeMillis();
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile RemoteServer instance;
    static Context appContext;

    private static volatile Activity activeHomeActivity;
    private static volatile Object activeHomeViewRef;

    private static volatile String currentPairToken;
    private static volatile long pairTokenTime;
    private static final long PAIR_TIMEOUT_MS = 10 * 60_000L;

    public interface RemoteInputListener {
        void onRemotePointerMove(float dx, float dy);
        void onRemotePointerClick();
    }

    private static volatile RemoteInputListener inputListener;
    private static volatile Adb persistentAdb;
    private static final Object ADB_LOCK = new Object();
    private static volatile float lastPointerX = 640, lastPointerY = 360;

    private static final Set<OutputStream> wsClients = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, byte[]> ICON_CACHE = new ConcurrentHashMap<>();

    private static final Object UDP_LOCK = new Object();
    private static volatile DatagramSocket mouseUdpSocket;
    private static volatile InetAddress loopbackAddr;

    private static String lastBroadcastTrack = "";
    private static String lastBroadcastArtist = "";
    private static boolean lastBroadcastPlaying = false;
    private static volatile long lastSpotifyActionTimestamp = 0;

    public static void setActiveHome(Activity activity, Object homeView) {
        activeHomeActivity = activity;
        activeHomeViewRef = homeView;
    }

    public static void clearActiveHome(Activity activity) {
        if (activeHomeActivity == activity) {
            activeHomeActivity = null;
            activeHomeViewRef = null;
        }
    }

    public static void closeHomeModals() {
        MAIN_HANDLER.post(() -> {
            try {
                if (activeHomeViewRef instanceof HomeView) {
                    ((HomeView) activeHomeViewRef).closeAllModals();
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void selectTvTab(int tabIndex) {
        if (appContext != null) {
            try {
                Intent it = new Intent(appContext, HomeActivity.class);
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                appContext.startActivity(it);
            } catch (Throwable t) {
                try {
                    Intent hi = new Intent(Intent.ACTION_MAIN);
                    hi.addCategory(Intent.CATEGORY_HOME);
                    hi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(hi);
                } catch (Throwable ignored) {}
            }
        }
        try {
            Intent bc = new Intent("io.freemytv.ACTION");
            bc.putExtra("action", "selectTab");
            bc.putExtra("tab", tabIndex);
            appContext.sendBroadcast(bc);
        } catch (Throwable ignored) {}
        MAIN_HANDLER.post(() -> {
            try {
                if (activeHomeViewRef instanceof HomeView) {
                    HomeView hv = (HomeView) activeHomeViewRef;
                    hv.closeAllModals();
                    hv.selectTab(tabIndex);
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void toggleTvNotifications() {
        try {
            Intent bc = new Intent("io.freemytv.ACTION");
            bc.setPackage("io.freemytv");
            bc.putExtra("action", "openNotif");
            appContext.sendBroadcast(bc);

            Intent it = new Intent(appContext, HomeActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("action", "openNotif");
            appContext.startActivity(it);
        } catch (Throwable ignored) {}
    }

    public static void openTvSettings() {
        try {
            Intent bc = new Intent("io.freemytv.ACTION");
            bc.putExtra("action", "openSettings");
            appContext.sendBroadcast(bc);
        } catch (Throwable ignored) {}
        MAIN_HANDLER.post(() -> {
            try {
                if (activeHomeViewRef instanceof HomeView) {
                    ((HomeView) activeHomeViewRef).closeAllModals();
                    ((HomeView) activeHomeViewRef).openAndroidSettings();
                    return;
                }
            } catch (Throwable ignored) {}

            try {
                Intent it = new Intent();
                it.setComponent(new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"));
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(it);
            } catch (Throwable t) {
                try {
                    Intent fallback = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(fallback);
                } catch (Throwable ignored) {}
            }
        });
    }

    public static void broadcastWs(JSONObject msg) {
        byte[] data = msg.toString().getBytes(StandardCharsets.UTF_8);
        synchronized (wsClients) {
            Iterator<OutputStream> it = wsClients.iterator();
            while (it.hasNext()) {
                OutputStream out = it.next();
                try {
                    sendWsFrame(out, 1, data);
                } catch (Throwable e) {
                    it.remove();
                }
            }
        }
    }

    public static void broadcastReload() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "reload");
            broadcastWs(obj);
        } catch (Throwable ignored) {}
    }

    public static void broadcastSpotifyState() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "spotify");
            obj.put("data", getSpotifyStateJson());
            broadcastWs(obj);
        } catch (Throwable ignored) {}
    }

    public static void broadcastFavoriteApps() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "apps");
            obj.put("data", new JSONArray(getInstalledAppsJson()));
            broadcastWs(obj);
        } catch (Throwable ignored) {}
    }

    public static void broadcastNotifications() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type", "notifications");
            obj.put("data", getNotificationsJson());
            broadcastWs(obj);
        } catch (Throwable ignored) {}
    }

    public static JSONObject getSpotifyStateJson() {
        JSONObject obj = new JSONObject();
        try {
            String track = NowPlaying.getActiveTrack();
            String artist = NowPlaying.getActiveArtist();
            boolean playing = NowPlaying.isMediaPlaying();
            String pkg = NowPlaying.getActivePkg();
            String artUrl = NowPlaying.getActiveArtUrl();
            boolean hasArt = (NowPlaying.getActiveArt() != null) || (artUrl != null && !artUrl.isEmpty());

            // Direct MediaSessionManager fallback
            if ((track == null || track.isEmpty()) && appContext != null) {
                MediaSessionManager m = (MediaSessionManager) appContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
                ComponentName comp = new ComponentName(appContext, NowPlaying.class);
                List<MediaController> list = m.getActiveSessions(comp);
                if (list != null) {
                    for (MediaController mc : list) {
                        MediaMetadata mm = mc.getMetadata();
                        if (mm != null) {
                            CharSequence t = mm.getText(MediaMetadata.METADATA_KEY_TITLE);
                            if (t == null) {
                                MediaDescription md = mm.getDescription();
                                if (md != null) t = md.getTitle();
                            }
                            if (t != null) track = t.toString().trim();

                            CharSequence a = mm.getText(MediaMetadata.METADATA_KEY_ARTIST);
                            if (a == null) {
                                MediaDescription md = mm.getDescription();
                                if (md != null) a = md.getSubtitle();
                            }
                            if (a != null) artist = a.toString().trim();

                            if (artUrl == null) {
                                String u = mm.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                                if (u == null) u = mm.getString(MediaMetadata.METADATA_KEY_ART_URI);
                                if (u != null) artUrl = u;
                            }

                            PlaybackState ps = mc.getPlaybackState();
                            if (ps != null && (ps.getState() == PlaybackState.STATE_PLAYING || ps.getState() == PlaybackState.STATE_BUFFERING)) {
                                playing = true;
                            }
                            pkg = mc.getPackageName();
                            if (track != null && !track.isEmpty()) break;
                        }
                    }
                }
            }

            if ((artUrl != null && !artUrl.isEmpty()) || NowPlaying.getActiveArt() != null) {
                hasArt = true;
            }

            obj.put("track", track != null ? track : "");
            obj.put("artist", artist != null ? artist : "");
            obj.put("playing", playing);
            obj.put("pkg", pkg != null ? pkg : "");
            obj.put("artUrl", artUrl != null ? artUrl : "");
            obj.put("hasArt", hasArt);
            obj.put("position", NowPlaying.getActivePositionMs());
            obj.put("duration", NowPlaying.getActiveDurationMs());
        } catch (Throwable ignored) {}
        return obj;
    }

    public static JSONObject getNotificationsJson() {
        JSONObject res = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            List<NowPlaying.NotificationItem> items = NowPlaying.activeNotifications;
            if (items != null) {
                for (NowPlaying.NotificationItem it : items) {
                    JSONObject o = new JSONObject();
                    o.put("key", it.key != null ? it.key : "");
                    o.put("pkg", it.pkg != null ? it.pkg : "");
                    o.put("appName", it.appName != null ? it.appName : "");
                    o.put("title", it.title != null ? it.title : "");
                    o.put("text", it.text != null ? it.text : "");
                    o.put("time", it.time);
                    arr.put(o);
                }
            }
            res.put("count", items != null ? items.size() : 0);
            res.put("items", arr);
        } catch (Throwable ignored) {}
        return res;
    }

    public static void runAdbCommand(String cmd) {
        POOL.execute(() -> {
            synchronized (ADB_LOCK) {
                try (Adb adb = Adb.connect(appContext, 2000, null)) {
                    if (adb != null) {
                        adb.shell(cmd);
                        return;
                    }
                } catch (Throwable t) {
                    AppLog.w("runAdbCommand error: " + android.util.Log.getStackTraceString(t));
                    try {
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    public static void searchUniTv(String title) {
        String cleanTitle = "";
        if (title != null) {
            cleanTitle = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                    .replaceAll("[^\\p{ASCII}]", "")
                    .replaceAll("[^a-zA-Z0-9\\s_\\.-]", "")
                    .trim();
        }
        final String finalTitle = cleanTitle;
        if (finalTitle.isEmpty()) return;
        sendUdpCommand("S " + finalTitle);
    }

    public static void ensureVirtualMouseDaemon() {
        POOL.execute(() -> {
            File target = null;
            try {
                File dir = appContext != null ? appContext.getFilesDir() : null;
                if (dir != null) {
                    target = new File(dir, "nexus_mouse");
                }
            } catch (Throwable ignored) {}
            if (target == null) {
                target = new File("/data/local/tmp/nexus_mouse");
            }
            try {
                long assetLen = 0;
                try (android.content.res.AssetFileDescriptor afd = appContext.getAssets().openFd("nexus_mouse")) {
                    assetLen = afd.getLength();
                } catch (Throwable ignored) {}
                if (!target.exists() || target.length() == 0 || (assetLen > 0 && target.length() != assetLen)) {
                    try (InputStream in = appContext.getAssets().open("nexus_mouse");
                         FileOutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[4096];
                        int r;
                        while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    }
                }
                target.setExecutable(true, false);
            } catch (Throwable ignored) {}

            try {
                File klTmp = new File(appContext.getCacheDir(), "Vendor_1234_Product_5678.kl");
                try (InputStream in = appContext.getAssets().open("Vendor_1234_Product_5678.kl");
                     FileOutputStream out = new FileOutputStream(klTmp)) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                String klPath = klTmp.getAbsolutePath();
                String cmd = "mount -o remount,rw /system 2>/dev/null; "
                           + "cp " + klPath + " /system/usr/keylayout/Vendor_1234_Product_5678.kl 2>/dev/null; "
                           + "chmod 644 /system/usr/keylayout/Vendor_1234_Product_5678.kl 2>/dev/null; "
                           + "mkdir -p /data/system/devices/keylayout 2>/dev/null; "
                           + "cp " + klPath + " /data/system/devices/keylayout/Vendor_1234_Product_5678.kl 2>/dev/null; "
                           + "chmod 644 /data/system/devices/keylayout/Vendor_1234_Product_5678.kl 2>/dev/null";
                runAdbCommand(cmd);
            } catch (Throwable ignored) {}

            try {
                File scTmp = new File(appContext.getCacheDir(), "unitv_search.sh");
                try (InputStream in = appContext.getAssets().open("unitv_search.sh");
                     FileOutputStream out = new FileOutputStream(scTmp)) {
                    byte[] buf = new byte[4096];
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                }
                String scPath = scTmp.getAbsolutePath();
                String cmd = "cp " + scPath + " /data/local/tmp/unitv_search.sh 2>/dev/null; "
                           + "chmod 755 /data/local/tmp/unitv_search.sh 2>/dev/null";
                runAdbCommand(cmd);
            } catch (Throwable ignored) {}

            try {
                String path = target.getAbsolutePath();
                Runtime.getRuntime().exec(new String[]{"sh", "-c", "chmod 755 " + path + "; pgrep nexus_mouse >/dev/null || nohup " + path + " </dev/null >/dev/null 2>&1 &"});
            } catch (Throwable ignored) {}
            ensureSystemOptimizations();
        });
    }

    public static void ensureSystemOptimizations() {
        POOL.execute(() -> {
            try {
                String cmd = "echo 1 > /sys/module/rockchip_pwm_remotectl/parameters/code_print 2>/dev/null; "
                           + "if ! grep -q zram0 /proc/swaps 2>/dev/null; then "
                           + "  echo 1 > /sys/devices/virtual/block/zram0/reset 2>/dev/null; "
                           + "  echo lzo > /sys/devices/virtual/block/zram0/comp_algorithm 2>/dev/null; "
                           + "  echo 402653184 > /sys/devices/virtual/block/zram0/disksize 2>/dev/null; "
                           + "  mkswap /dev/block/zram0 2>/dev/null; "
                           + "  swapon /dev/block/zram0 2>/dev/null; "
                           + "fi; "
                           + "echo 80 > /proc/sys/vm/swappiness 2>/dev/null; "
                           + "echo 100 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null; "
                           + "if [ -d /sys/devices/system/cpu/cpufreq/interactive ]; then "
                           + "  echo 1 > /sys/devices/system/cpu/cpufreq/interactive/io_is_busy 2>/dev/null; "
                           + "  echo 70 > /sys/devices/system/cpu/cpufreq/interactive/go_hispeed_load 2>/dev/null; "
                           + "fi; "
                           + "echo 512 > /sys/block/mmcblk0/queue/read_ahead_kb 2>/dev/null; "
                           + "echo 3 > /proc/sys/vm/drop_caches 2>/dev/null; "
                           + "pm trim-caches 1000M 2>/dev/null; ";
                runAdbCommand(cmd);
            } catch (Throwable ignored) {}
        });
    }

    private static void sendUdpCommand(String cmd) {
        POOL.execute(() -> {
            synchronized (UDP_LOCK) {
                try {
                    if (loopbackAddr == null) {
                        try {
                            loopbackAddr = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
                        } catch (Throwable e) {
                            loopbackAddr = InetAddress.getLoopbackAddress();
                        }
                    }
                    if (mouseUdpSocket == null || mouseUdpSocket.isClosed()) {
                        mouseUdpSocket = new DatagramSocket(new InetSocketAddress(loopbackAddr, 0));
                    }
                    byte[] data = cmd.getBytes(StandardCharsets.US_ASCII);
                    DatagramPacket packet = new DatagramPacket(data, data.length, loopbackAddr, 8766);
                    mouseUdpSocket.send(packet);
                } catch (Throwable t) {
                    AppLog.w("sendUdpCommand FAIL: " + cmd + " -> " + t.getMessage());
                    try {
                        if (mouseUdpSocket != null) mouseUdpSocket.close();
                    } catch (Throwable ignored) {}
                    mouseUdpSocket = null;
                }
            }
        });
    }

    public static void sendRealMouseRel(int dx, int dy) {
        sendUdpCommand("M " + dx + " " + dy);
    }

    public static void sendRealScroll(int wheel) {
        sendUdpCommand("W " + wheel);
    }

    public static void sendRealMouseClick() {
        sendUdpCommand("C");
    }

    public static void sendRealKey(int linuxKey) {
        sendUdpCommand("K " + linuxKey);
    }

    public static void sendRealKeyDown(int linuxKey) {
        sendUdpCommand("D " + linuxKey);
    }

    public static void sendRealKeyUp(int linuxKey) {
        sendUdpCommand("U " + linuxKey);
    }

    public static void setInputListener(RemoteInputListener listener) {
        inputListener = listener;
    }

    private final ServerSocket serverSocket;
    private volatile boolean running = true;

    private RemoteServer(Context context, InetAddress lan) throws IOException {
        appContext = context.getApplicationContext();
        ServerSocket ss = null;
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            char[] pwd = "nexustv".toCharArray();
            try (InputStream is = appContext.getAssets().open("server.p12")) {
                ks.load(is, pwd);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pwd);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(kmf.getKeyManagers(), null, null);
            SSLServerSocketFactory ssf = sslContext.getServerSocketFactory();
            ss = ssf.createServerSocket(PORT, 50, lan);
            AppLog.w("RemoteServer initialized with TLS on https://" + lan.getHostAddress() + ":" + PORT);
        } catch (Throwable e) {
            AppLog.w("RemoteServer SSL init failed, using plain HTTP: " + e.getMessage());
            ss = new ServerSocket(PORT, 50, lan);
        }
        serverSocket = ss;
        ensureVirtualMouseDaemon();
        POOL.execute(this::listen);

        // Real-time media heartbeat: broadcasts spotify updates over WebSocket
        SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                checkAndBroadcastMedia();
            } catch (Throwable ignored) {}
        }, 1, 1, TimeUnit.SECONDS);
    }

    private static void checkAndBroadcastMedia() {
        if (wsClients.isEmpty()) return;
        JSONObject cur = getSpotifyStateJson();
        String trk = cur.optString("track", "");
        String art = cur.optString("artist", "");
        boolean ply = cur.optBoolean("playing", false);
        if (!trk.equals(lastBroadcastTrack) || !art.equals(lastBroadcastArtist) || ply != lastBroadcastPlaying) {
            lastBroadcastTrack = trk;
            lastBroadcastArtist = art;
            lastBroadcastPlaying = ply;
            broadcastSpotifyState();
        }
    }

    public static synchronized void start(Context context) {
        if (instance != null) return;
        appContext = context.getApplicationContext();
        InetAddress lan = KeyPage.lanAddress(context);
        if (lan == null) {
            AppLog.w("RemoteServer: no LAN address found, cannot start");
            return;
        }
        try {
            instance = new RemoteServer(context, lan);
            AppLog.w("RemoteServer started on https://" + lan.getHostAddress() + ":" + PORT);
        } catch (IOException e) {
            AppLog.w("RemoteServer failed to start on port " + PORT + ": " + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (instance != null) {
            instance.running = false;
            try {
                instance.serverSocket.close();
            } catch (IOException ignored) {}
            instance = null;
        }
    }

    public static synchronized String generateNewPairToken() {
        SecureRandom sr = new SecureRandom();
        int val = 100000 + sr.nextInt(900000);
        currentPairToken = String.valueOf(val);
        pairTokenTime = SystemClock.elapsedRealtime();
        Prefs.saveRecentPairToken(currentPairToken);
        return currentPairToken;
    }

    public static synchronized String getCurrentPairToken() {
        if (currentPairToken == null || (SystemClock.elapsedRealtime() - pairTokenTime > PAIR_TIMEOUT_MS)) {
            return generateNewPairToken();
        }
        return currentPairToken;
    }

    public static String getBaseUrl(Context context) {
        InetAddress lan = KeyPage.lanAddress(context);
        if (lan == null) return "https://localhost:" + PORT;
        return "https://" + lan.getHostAddress() + ":" + PORT;
    }

    public static String getRemoteUrl(Context context) {
        InetAddress lan = KeyPage.lanAddress(context);
        if (lan == null) return "https://localhost:" + PORT + "/";
        return "https://" + lan.getHostAddress() + ":" + PORT + "/?pair=" + getCurrentPairToken();
    }

    private void listen() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                POOL.execute(() -> handleClient(socket));
            } catch (IOException e) {
                if (!running) break;
            }
        }
    }

    private static String readLineRaw(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }

    private static void readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(b, off + total, len - total);
            if (r < 0) throw new EOFException("EOF while reading socket");
            total += r;
        }
    }

    private void handleClient(Socket socket) {
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            String requestLine = readLineRaw(in);
            if (requestLine == null || requestLine.isEmpty()) {
                socket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                socket.close();
                return;
            }

            String method = parts[0];
            String fullPath = parts[1];
            String path = fullPath;
            String query = "";
            int qIdx = fullPath.indexOf('?');
            if (qIdx != -1) {
                path = fullPath.substring(0, qIdx);
                query = fullPath.substring(qIdx + 1);
            }

            String line;
            int contentLength = 0;
            String authHeader = "";
            boolean isWebSocketUpgrade = false;
            String wsKey = "";

            while ((line = readLineRaw(in)) != null && !line.isEmpty()) {
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (NumberFormatException ignored) {}
                } else if (lower.startsWith("authorization:")) {
                    authHeader = line.substring(14).trim();
                } else if (lower.startsWith("upgrade:") && lower.contains("websocket")) {
                    isWebSocketUpgrade = true;
                } else if (lower.startsWith("sec-websocket-key:")) {
                    wsKey = line.substring(18).trim();
                }
            }

            if (isWebSocketUpgrade && !wsKey.isEmpty()) {
                handleWebSocketSession(wsKey, in, out);
                return;
            }

            String body = "";
            if (contentLength > 0 && contentLength < 65536) {
                byte[] buf = new byte[contentLength];
                readFully(in, buf, 0, contentLength);
                body = new String(buf, StandardCharsets.UTF_8);
            }

            route(method, path, query, authHeader, body, out);
            socket.close();

        } catch (Exception ignored) {
            try { socket.close(); } catch (Throwable ignored2) {}
        }
    }

    private void handleWebSocketSession(String wsKey, InputStream in, OutputStream out) throws IOException {
        String acceptKey = computeWebSocketAccept(wsKey);
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.UTF_8));
        out.flush();

        wsClients.add(out);
        try {
            JSONObject initMsg = new JSONObject();
            initMsg.put("type", "init");
            initMsg.put("version", BUILD_ID);
            initMsg.put("spotify", getSpotifyStateJson());
            initMsg.put("apps", new JSONArray(getInstalledAppsJson()));
            initMsg.put("notifications", getNotificationsJson());
            sendWsFrame(out, 1, initMsg.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {}

        try {
            byte[] header = new byte[2];
            while (running) {
                readFully(in, header, 0, 2);
                int b0 = header[0] & 0xFF;
                int b1 = header[1] & 0xFF;
                int opcode = b0 & 0x0F;

                if (opcode == 8) break; // Close
                if (opcode == 9) { // Ping -> Pong
                    sendWsFrame(out, 10, new byte[0]);
                    continue;
                }

                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;
                if (payloadLen == 126) {
                    byte[] ext = new byte[2];
                    readFully(in, ext, 0, 2);
                    payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                } else if (payloadLen == 127) {
                    byte[] ext = new byte[8];
                    readFully(in, ext, 0, 8);
                    payloadLen = ByteBuffer.wrap(ext).getLong();
                }

                byte[] mask = new byte[4];
                if (masked) readFully(in, mask, 0, 4);

                if (payloadLen > 1024 * 1024) break; // 1MB limit
                byte[] payload = new byte[(int) payloadLen];
                readFully(in, payload, 0, (int) payloadLen);

                if (masked) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i % 4];
                    }
                }

                if (opcode == 1) { // Text frame
                    String text = new String(payload, StandardCharsets.UTF_8);
                    handleWsMessage(text);
                }
            }
        } catch (EOFException ignored) {
            // Normal client disconnect
        } catch (Exception e) {
            AppLog.w("WebSocket error: " + e.getMessage());
        } finally {
            wsClients.remove(out);
        }
    }

    private static void sendWsFrame(OutputStream out, int opcode, byte[] data) throws IOException {
        int len = data.length;
        if (len < 126) {
            out.write(new byte[]{(byte) (0x80 | opcode), (byte) len});
        } else if (len <= 65535) {
            out.write(new byte[]{(byte) (0x80 | opcode), 126, (byte) (len >> 8), (byte) (len & 0xFF)});
        } else {
            out.write(new byte[]{
                (byte) (0x80 | opcode), 127,
                (byte) (len >> 56), (byte) (len >> 48), (byte) (len >> 40), (byte) (len >> 32),
                (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) (len & 0xFF)
            });
        }
        if (len > 0) out.write(data);
        out.flush();
    }

    private static String computeWebSocketAccept(String key) {
        try {
            String input = key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.US_ASCII));
            return Base64.encodeToString(hash, Base64.NO_WRAP);
        } catch (Exception e) {
            return "";
        }
    }

    private void handleWsMessage(String text) {
        try {
            JSONObject obj = new JSONObject(text);
            String type = obj.optString("type", "");
            if ("pointer".equals(type)) {
                float dx = (float) obj.optDouble("dx", 0);
                float dy = (float) obj.optDouble("dy", 0);
                sendRealMouseRel(Math.round(dx), Math.round(dy));
            } else if ("scroll".equals(type)) {
                int wheel = obj.optInt("wheel", obj.optInt("dy", 0));
                sendRealScroll(wheel);
            } else if ("click".equals(type)) {
                executeKey("enter");
            } else if ("key".equals(type)) {
                String key = obj.optString("key", "");
                executeKey(key);
            } else if ("keydown".equals(type)) {
                String key = obj.optString("key", "");
                executeKeyDown(key);
            } else if ("keyup".equals(type)) {
                String key = obj.optString("key", "");
                executeKeyUp(key);
            } else if ("launch".equals(type)) {
                String pkg = obj.optString("pkg", "");
                launchApp(pkg);
            } else if ("nav".equals(type)) {
                String target = obj.optString("target", "");
                if ("home".equals(target)) selectTvTab(0);
                else if ("movies".equals(target)) selectTvTab(1);
                else if ("series".equals(target)) selectTvTab(2);
                else if ("notif".equals(target)) toggleTvNotifications();
                else if ("settings".equals(target)) openTvSettings();
            } else if ("spotify".equals(type)) {
                String action = obj.optString("action", "");
                handleSpotifyAction(action);
            } else if ("notif_dismiss".equals(type)) {
                String key = obj.optString("key", "");
                NowPlaying.dismissNotification(key);
            } else if ("notif_clear".equals(type)) {
                NowPlaying.dismissAllNotifications();
            } else if ("optimize".equals(type)) {
                ensureSystemOptimizations();
            }
        } catch (Exception ignored) {}
    }

    public static void handleSpotifyAction(String action) {
        long now = System.currentTimeMillis();
        if (now - lastSpotifyActionTimestamp < 350) return;
        lastSpotifyActionTimestamp = now;

        if ("playPause".equals(action)) {
            JSONObject st = getSpotifyStateJson();
            boolean isPlaying = st.optBoolean("playing", false) || NowPlaying.isMediaPlaying();
            if (isPlaying) {
                // User paused: only pause and close Spotify app
                NowPlaying.pause();
                closeSpotify();
            } else {
                // User played: launch Spotify on TV
                launchApp("com.spotify.tv.android");
                POOL.execute(() -> {
                    try { Thread.sleep(1200); } catch (Exception ignored) {}
                    NowPlaying.play();
                });
            }
        } else if ("pause".equals(action)) {
            NowPlaying.pause();
            closeSpotify();
        } else if ("play".equals(action)) {
            launchApp("com.spotify.tv.android");
            POOL.execute(() -> {
                try { Thread.sleep(1200); } catch (Exception ignored) {}
                NowPlaying.play();
            });
        } else if ("next".equals(action)) {
            NowPlaying.skipNext();
        } else if ("prev".equals(action)) {
            NowPlaying.skipPrev();
        } else if ("launch".equals(action)) {
            launchApp("com.spotify.tv.android");
        }

        final JSONObject initialState = getSpotifyStateJson();
        final String oldTrack = initialState.optString("track", "");

        POOL.execute(() -> {
            for (int i = 0; i < 25; i++) {
                try { Thread.sleep(150); } catch (Exception ignored) {}
                if (NowPlaying.activeWatch != null) {
                    MAIN_HANDLER.post(() -> {
                        try {
                            if (NowPlaying.activeWatch != null) NowPlaying.activeWatch.update();
                        } catch (Throwable ignored) {}
                    });
                }
                JSONObject cur = getSpotifyStateJson();
                String curTrack = cur.optString("track", "");
                boolean curPlaying = cur.optBoolean("playing", false);
                if (!curTrack.isEmpty() && (!curTrack.equals(oldTrack) || "playPause".equals(action) || "pause".equals(action))) {
                    lastBroadcastTrack = curTrack;
                    lastBroadcastArtist = cur.optString("artist", "");
                    lastBroadcastPlaying = curPlaying;
                    broadcastSpotifyState();
                    break;
                }
            }
        });
    }

    public static void closeSpotify() {
        POOL.execute(() -> {
            try {
                Thread.sleep(120);
                if (activeHomeActivity != null) {
                    Intent it = new Intent(appContext, HomeActivity.class);
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    appContext.startActivity(it);
                } else if (appContext != null) {
                    Intent it = new Intent(Intent.ACTION_MAIN);
                    it.addCategory(Intent.CATEGORY_HOME);
                    it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(it);
                }
                runAdbCommand("am force-stop com.spotify.tv.android");
                lastBroadcastPlaying = false;
                broadcastSpotifyState();
            } catch (Throwable ignored) {}
        });
    }

    public static void launchApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return;
        try {
            Intent it = appContext.getPackageManager().getLaunchIntentForPackage(pkg);
            if (it != null) {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                appContext.startActivity(it);
            }
        } catch (Throwable ignored) {}
    }

    private void route(String method, String path, String query, String authHeader, String body, OutputStream out) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(method)) {
            sendResponse(out, 200, "text/plain", "OK");
            return;
        }

        if ("/manifest.json".equals(path)) {
            sendResponse(out, 200, "application/manifest+json; charset=utf-8", getRenderedManifest());
            return;
        }

        if ("/sw.js".equals(path)) {
            sendResponse(out, 200, "application/javascript; charset=utf-8", getRenderedSwJs());
            return;
        }

        if ("/css/style.css".equals(path)) {
            sendResponse(out, 200, "text/css; charset=utf-8", loadAssetText("web/css/style.css"));
            return;
        }

        if ("/css/materialdesignicons.min.css".equals(path)) {
            sendResponse(out, 200, "text/css; charset=utf-8", loadAssetText("web/css/materialdesignicons.min.css"));
            return;
        }

        if (path.startsWith("/fonts/")) {
            String fontPath = path;
            int q = fontPath.indexOf('?');
            if (q >= 0) fontPath = fontPath.substring(0, q);
            byte[] bytes = loadAssetBytes("web" + fontPath);
            if (bytes != null && bytes.length > 0) {
                sendByteResponse(out, 200, "font/woff2", bytes);
                return;
            }
        }

        if (path.startsWith("/icons/")) {
            String iconPath = path;
            int q = iconPath.indexOf('?');
            if (q >= 0) iconPath = iconPath.substring(0, q);
            byte[] bytes = loadAssetBytes("web" + iconPath);
            if (bytes != null && bytes.length > 0) {
                sendByteResponse(out, 200, "image/png", bytes);
                return;
            }
        }

        if ("/favicon.ico".equals(path)) {
            byte[] bytes = loadAssetBytes("web/icons/icon-192.png");
            if (bytes != null && bytes.length > 0) {
                sendByteResponse(out, 200, "image/png", bytes);
                return;
            }
        }

        if ("/js/app.js".equals(path)) {
            sendResponse(out, 200, "application/javascript; charset=utf-8", getRenderedAppJs());
            return;
        }

        if ("/remote.html".equals(path)) {
            sendResponse(out, 200, "text/html; charset=utf-8", getRenderedPwaHtml());
            return;
        }

        if ("/api/apps".equals(path)) {
            sendResponse(out, 200, "application/json", getInstalledAppsJson());
            return;
        }

        if ("/api/status".equals(path)) {
            JSONObject st = new JSONObject();
            try {
                st.put("status", "ok");
                st.put("version", BUILD_ID);
                st.put("spotify", getSpotifyStateJson());
            } catch (Exception ignored) {}
            sendResponse(out, 200, "application/json", st.toString());
            return;
        }

        if ("/api/optimize".equals(path)) {
            ensureSystemOptimizations();
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            return;
        }

        if ("/api/app/icon".equals(path)) {
            handleAppIcon(query, out);
            return;
        }

        if ("/api/spotify/icon".equals(path) || "/spotify_icon.webp".equals(path)) {
            handleSpotifyIcon(out);
            return;
        }

        if ("/api/pair".equals(path) && "POST".equalsIgnoreCase(method)) {
            handlePair(body, query, out);
            return;
        }

        if ("/api/auth".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleAuth(body, authHeader, out);
            return;
        }

        if ("/api/logout".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleLogout(body, authHeader, out);
            return;
        }

        if ("/api/key".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleKey(body, out);
            return;
        }

        if ("/api/nav".equals(path) && "POST".equalsIgnoreCase(method)) {
            try {
                JSONObject obj = new JSONObject(body);
                String target = obj.optString("target", "");
                if ("home".equals(target)) selectTvTab(0);
                else if ("movies".equals(target)) selectTvTab(1);
                else if ("series".equals(target)) selectTvTab(2);
                else if ("notif".equals(target)) toggleTvNotifications();
                else if ("settings".equals(target)) openTvSettings();
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } catch (Exception e) {
                sendResponse(out, 400, "application/json", "{\"error\":\"invalid nav\"}");
            }
            return;
        }

        if ("/api/notifications".equals(path)) {
            sendResponse(out, 200, "application/json", getNotificationsJson().toString());
            return;
        }

        if ("/api/notifications/dismiss".equals(path) && "POST".equalsIgnoreCase(method)) {
            try {
                JSONObject obj = new JSONObject(body);
                String key = obj.optString("key", "");
                NowPlaying.dismissNotification(key);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } catch (Exception e) {
                sendResponse(out, 400, "application/json", "{\"error\":\"dismiss failed\"}");
            }
            return;
        }

        if ("/api/notifications/clear".equals(path) && "POST".equalsIgnoreCase(method)) {
            NowPlaying.dismissAllNotifications();
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            return;
        }

        if ("/api/spotify/state".equals(path)) {
            sendResponse(out, 200, "application/json", getSpotifyStateJson().toString());
            return;
        }

        if ("/api/spotify/art".equals(path)) {
            String artUrl = NowPlaying.getActiveArtUrl();
            if (artUrl != null && (artUrl.startsWith("http://") || artUrl.startsWith("https://"))) {
                sendRedirect(out, artUrl);
                return;
            }
            Bitmap art = NowPlaying.getActiveArt();
            if (art != null && !art.isRecycled()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                art.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                sendByteResponse(out, 200, "image/jpeg", baos.toByteArray());
            } else {
                handleSpotifyIcon(out);
            }
            return;
        }

        if ("/api/spotify/action".equals(path) && "POST".equalsIgnoreCase(method)) {
            try {
                JSONObject obj = new JSONObject(body);
                String action = obj.optString("action", "");
                handleSpotifyAction(action);
                sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            } catch (Exception e) {
                sendResponse(out, 400, "application/json", "{\"error\":\"action failed\"}");
            }
            return;
        }

        if ("/api/reload".equals(path)) {
            broadcastReload();
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
            return;
        }

        if ("/api/pointer".equals(path) && "POST".equalsIgnoreCase(method)) {
            handlePointer(body, out);
            return;
        }

        if ("/api/scroll".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleScroll(body, out);
            return;
        }

        if ("/api/click".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleClick(body, out);
            return;
        }

        if ("/api/launch".equals(path) && "POST".equalsIgnoreCase(method)) {
            handleLaunch(body, out);
            return;
        }

        // Default: serve the Nexus TV Remote PWA HTML
        sendResponse(out, 200, "text/html; charset=utf-8", getRenderedPwaHtml());
    }

    private void handlePair(String body, String query, OutputStream out) throws IOException {
        String tokenToVerify = "";
        try {
            if (!body.isEmpty()) {
                JSONObject obj = new JSONObject(body);
                tokenToVerify = obj.optString("pairToken", "");
                if (tokenToVerify.isEmpty()) tokenToVerify = obj.optString("code", "");
            }
        } catch (Exception ignored) {}

        if (tokenToVerify.isEmpty() && query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("pair=")) {
                    tokenToVerify = param.substring(5);
                    break;
                } else if (param.startsWith("code=")) {
                    tokenToVerify = param.substring(5);
                    break;
                }
            }
        }

        if (tokenToVerify != null) {
            tokenToVerify = tokenToVerify.trim();
            int q = tokenToVerify.indexOf('?');
            if (q >= 0) tokenToVerify = tokenToVerify.substring(0, q);
            int amp = tokenToVerify.indexOf('&');
            if (amp >= 0) tokenToVerify = tokenToVerify.substring(0, amp);
        }

        boolean match = (tokenToVerify != null && !tokenToVerify.isEmpty()) && (
                (currentPairToken != null && currentPairToken.equalsIgnoreCase(tokenToVerify)
                        && (SystemClock.elapsedRealtime() - pairTokenTime <= PAIR_TIMEOUT_MS))
                || Prefs.isValidRecentPairToken(tokenToVerify)
        );

        if (match) {
            byte[] rnd = new byte[16];
            new SecureRandom().nextBytes(rnd);
            StringBuilder sb = new StringBuilder();
            for (byte b : rnd) {
                sb.append(String.format("%02x", b));
            }
            String permanentToken = sb.toString();
            Prefs.addPairedRemoteToken(permanentToken);

            MAIN_HANDLER.post(() -> {
                try {
                    android.widget.Toast.makeText(appContext, "Smartphone conectado ao Nexus TV!", android.widget.Toast.LENGTH_SHORT).show();
                } catch (Throwable ignored) {}
            });

            closeHomeModals();

            JSONObject resp = new JSONObject();
            try {
                resp.put("success", true);
                resp.put("token", permanentToken);
            } catch (Exception ignored) {}
            sendResponse(out, 200, "application/json", resp.toString());
        } else {
            JSONObject resp = new JSONObject();
            try {
                resp.put("success", false);
                resp.put("error", "Código de emparelhamento inválido ou expirado.");
            } catch (Exception ignored) {}
            sendResponse(out, 403, "application/json", resp.toString());
        }
    }

    private void handleAuth(String body, String authHeader, OutputStream out) throws IOException {
        String token = extractToken(body, authHeader);
        boolean ok = Prefs.isRemoteTokenAuthorized(token);
        JSONObject resp = new JSONObject();
        try {
            resp.put("authorized", ok);
        } catch (Exception ignored) {}
        sendResponse(out, ok ? 200 : 401, "application/json", resp.toString());
    }

    private void handleLogout(String body, String authHeader, OutputStream out) throws IOException {
        String token = extractToken(body, authHeader);
        if (!token.isEmpty()) {
            Prefs.removePairedRemoteToken(token);
        }
        JSONObject resp = new JSONObject();
        try {
            resp.put("success", true);
        } catch (Exception ignored) {}
        sendResponse(out, 200, "application/json", resp.toString());
    }

    private void handleKey(String body, OutputStream out) throws IOException {
        try {
            JSONObject obj = new JSONObject(body);
            String key = obj.optString("key", "");
            executeKey(key);
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
        } catch (Exception e) {
            sendResponse(out, 400, "application/json", "{\"error\":\"invalid json\"}");
        }
    }

    private void handlePointer(String body, OutputStream out) throws IOException {
        try {
            JSONObject obj = new JSONObject(body);
            float dx = (float) obj.optDouble("dx", 0);
            float dy = (float) obj.optDouble("dy", 0);
            sendRealMouseRel(Math.round(dx), Math.round(dy));
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
        } catch (Exception e) {
            sendResponse(out, 400, "application/json", "{\"error\":\"invalid pointer\"}");
        }
    }

    private void handleScroll(String body, OutputStream out) throws IOException {
        try {
            JSONObject obj = new JSONObject(body);
            int wheel = obj.optInt("wheel", obj.optInt("dy", 0));
            sendRealScroll(wheel);
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
        } catch (Exception e) {
            sendResponse(out, 400, "application/json", "{\"error\":\"invalid scroll\"}");
        }
    }

    private void handleClick(String body, OutputStream out) throws IOException {
        executeKey("enter");
        sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
    }

    private void handleLaunch(String body, OutputStream out) throws IOException {
        try {
            JSONObject obj = new JSONObject(body);
            String pkg = obj.optString("pkg", "");
            launchApp(pkg);
            sendResponse(out, 200, "application/json", "{\"status\":\"ok\"}");
        } catch (Exception e) {
            sendResponse(out, 400, "application/json", "{\"error\":\"launch failed\"}");
        }
    }

    private void handleSpotifyIcon(OutputStream out) throws IOException {
        try (InputStream is = appContext.getAssets().open("spotify_icon.webp")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = is.read(buf)) != -1) baos.write(buf, 0, r);
            sendByteResponse(out, 200, "image/webp", baos.toByteArray());
        } catch (Throwable t) {
            sendResponse(out, 404, "text/plain", "Icon missing");
        }
    }

    private void handleAppIcon(String query, OutputStream out) throws IOException {
        String pkg = "";
        if (query != null && query.contains("pkg=")) {
            for (String param : query.split("&")) {
                if (param.startsWith("pkg=")) {
                    pkg = URLDecoder.decode(param.substring(4), "UTF-8");
                    break;
                }
            }
        }
        if (pkg.isEmpty()) {
            sendResponse(out, 404, "text/plain", "Package missing");
            return;
        }
        byte[] cached = ICON_CACHE.get(pkg);
        if (cached == null) {
            try {
                PackageManager pm = appContext.getPackageManager();
                Drawable iconDrawable = pm.getApplicationIcon(pkg);
                Bitmap bmp = drawableToBitmap(iconDrawable, 128, 128);
                if (bmp != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos);
                    cached = baos.toByteArray();
                    ICON_CACHE.put(pkg, cached);
                }
            } catch (Throwable ignored) {}
        }
        if (cached != null) {
            sendByteResponse(out, 200, "image/png", cached);
        } else {
            sendResponse(out, 404, "text/plain", "Icon not found");
        }
    }

    private static Bitmap drawableToBitmap(Drawable drawable, int w, int h) {
        if (drawable instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) drawable).getBitmap();
            if (b != null) return b;
        }
        Bitmap bitmap = Bitmap.createBitmap(w > 0 ? w : 128, h > 0 ? h : 128, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private String extractToken(String body, String authHeader) {
        if (authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        try {
            if (!body.isEmpty()) {
                JSONObject obj = new JSONObject(body);
                return obj.optString("token", "");
            }
        } catch (Exception ignored) {}
        return "";
    }

    private void executeKey(String key) {
        if (key == null || key.isEmpty()) return;
        String k = key.toLowerCase(Locale.ROOT).trim();

        int linuxCode = -1;
        int androidCode = -1;

        switch (k) {
            case "up":
            case "dpad_up":
                linuxCode = 103;
                androidCode = KeyEvent.KEYCODE_DPAD_UP;
                break;
            case "down":
            case "dpad_down":
                linuxCode = 108;
                androidCode = KeyEvent.KEYCODE_DPAD_DOWN;
                break;
            case "left":
            case "dpad_left":
                linuxCode = 105;
                androidCode = KeyEvent.KEYCODE_DPAD_LEFT;
                break;
            case "right":
            case "dpad_right":
                linuxCode = 106;
                androidCode = KeyEvent.KEYCODE_DPAD_RIGHT;
                break;
            case "ok":
            case "enter":
                linuxCode = 28;
                androidCode = KeyEvent.KEYCODE_ENTER;
                break;
            case "back":
                linuxCode = 158;
                androidCode = KeyEvent.KEYCODE_BACK;
                RemoteKeyService.performAction(AccessibilityService.GLOBAL_ACTION_BACK);
                break;
            case "home":
                linuxCode = 172;
                androidCode = KeyEvent.KEYCODE_HOME;
                closeHomeModals();
                RemoteKeyService.performAction(AccessibilityService.GLOBAL_ACTION_HOME);
                try {
                    Intent hi = new Intent(Intent.ACTION_MAIN);
                    hi.addCategory(Intent.CATEGORY_HOME);
                    hi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(hi);
                } catch (Throwable ignored) {}
                break;
            case "menu":
                linuxCode = 139;
                androidCode = KeyEvent.KEYCODE_MENU;
                break;
            case "recents":
                androidCode = KeyEvent.KEYCODE_APP_SWITCH;
                RemoteKeyService.performAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
                break;
            case "play":
            case "play_pause":
            case "playpause":
                linuxCode = 164;
                androidCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
                NowPlaying.playPause();
                break;
            case "vol_up":
                adjustVolume(AudioManager.ADJUST_RAISE);
                linuxCode = 115;
                androidCode = KeyEvent.KEYCODE_VOLUME_UP;
                break;
            case "vol_down":
                adjustVolume(AudioManager.ADJUST_LOWER);
                linuxCode = 114;
                androidCode = KeyEvent.KEYCODE_VOLUME_DOWN;
                break;
            case "mute":
                adjustVolume(AudioManager.ADJUST_TOGGLE_MUTE);
                linuxCode = 113;
                androidCode = KeyEvent.KEYCODE_VOLUME_MUTE;
                break;
            case "power":
                linuxCode = 116;
                androidCode = KeyEvent.KEYCODE_POWER;
                RemoteKeyService.performAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
                break;
        }

        if (linuxCode > 0) {
            sendRealKey(linuxCode);
        } else if (androidCode > 0) {
            final int code = androidCode;
            MAIN_HANDLER.post(() -> {
                try {
                    if (activeHomeActivity != null && activeHomeActivity.hasWindowFocus()) {
                        KeyEvent down = new KeyEvent(KeyEvent.ACTION_DOWN, code);
                        KeyEvent up = new KeyEvent(KeyEvent.ACTION_UP, code);
                        activeHomeActivity.dispatchKeyEvent(down);
                        activeHomeActivity.dispatchKeyEvent(up);
                    }
                } catch (Throwable ignored) {}
            });
        }
    }

    private int getLinuxKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        switch (key.toLowerCase(Locale.ROOT).trim()) {
            case "up":
            case "dpad_up":
                return 103;
            case "down":
            case "dpad_down":
                return 108;
            case "left":
            case "dpad_left":
                return 105;
            case "right":
            case "dpad_right":
                return 106;
            case "ok":
            case "enter":
                return 28;
            case "back":
                return 158;
            case "home":
                return 172;
            case "menu":
                return 139;
            case "play":
            case "play_pause":
            case "playpause":
                return 164;
            case "vol_up":
                return 115;
            case "vol_down":
                return 114;
            case "mute":
                return 113;
            case "power":
                return 116;
            default:
                return -1;
        }
    }

    private void executeKeyDown(String key) {
        if (key == null || key.isEmpty()) return;
        String k = key.toLowerCase(Locale.ROOT).trim();
        int linuxCode = getLinuxKey(k);
        if (linuxCode > 0) {
            sendRealKeyDown(linuxCode);
        } else {
            int androidCode = -1;
            if ("recents".equals(k)) androidCode = KeyEvent.KEYCODE_APP_SWITCH;
            if (androidCode > 0) {
                final int code = androidCode;
                MAIN_HANDLER.post(() -> {
                    try {
                        if (activeHomeActivity != null && activeHomeActivity.hasWindowFocus()) {
                            activeHomeActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
                        }
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    private void executeKeyUp(String key) {
        if (key == null || key.isEmpty()) return;
        String k = key.toLowerCase(Locale.ROOT).trim();
        int linuxCode = getLinuxKey(k);
        if (linuxCode > 0) {
            sendRealKeyUp(linuxCode);
        } else {
            int androidCode = -1;
            if ("recents".equals(k)) androidCode = KeyEvent.KEYCODE_APP_SWITCH;
            if (androidCode > 0) {
                final int code = androidCode;
                MAIN_HANDLER.post(() -> {
                    try {
                        if (activeHomeActivity != null && activeHomeActivity.hasWindowFocus()) {
                            activeHomeActivity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
                        }
                    } catch (Throwable ignored) {}
                });
            }
        }
    }

    private void adjustVolume(int direction) {
        try {
            AudioManager am = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Real synchronization with favorite apps ("Apps favoritos") of Nexus Launcher.
     */
    public static String getInstalledAppsJson() {
        try {
            JSONArray arr = new JSONArray();

            if (activeHomeViewRef instanceof HomeView) {
                List<HomeApps.Item> shelf = ((HomeView) activeHomeViewRef).getFavoriteShelf();
                if (shelf != null && !shelf.isEmpty()) {
                    for (HomeApps.Item item : shelf) {
                        if (item.pkg == null || item.pkg.isEmpty()) continue;
                        JSONObject appObj = new JSONObject();
                        appObj.put("label", item.label != null ? item.label : item.pkg);
                        appObj.put("pkg", item.pkg);
                        arr.put(appObj);
                    }
                    if (arr.length() > 0) return arr.toString();
                }
            }

            // Fallback to HomeApps.shelf
            List<HomeApps.Item> fallbackShelf = HomeApps.shelf(appContext, false);
            if (fallbackShelf != null && !fallbackShelf.isEmpty()) {
                for (HomeApps.Item item : fallbackShelf) {
                    if (item.pkg == null || item.pkg.isEmpty()) continue;
                    JSONObject appObj = new JSONObject();
                    appObj.put("label", item.label != null ? item.label : item.pkg);
                    appObj.put("pkg", item.pkg);
                    arr.put(appObj);
                }
                if (arr.length() > 0) return arr.toString();
            }

            // Fallback to Leanback / Launcher apps
            PackageManager pm = appContext.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);
            if (apps == null || apps.isEmpty()) {
                intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                apps = pm.queryIntentActivities(intent, 0);
            }

            if (apps != null) {
                for (ResolveInfo ri : apps) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (appContext.getPackageName().equals(pkg)) continue;
                    String label = ri.loadLabel(pm).toString();
                    JSONObject appObj = new JSONObject();
                    appObj.put("label", label);
                    appObj.put("pkg", pkg);
                    arr.put(appObj);
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private void sendResponse(OutputStream out, int status, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 403 ? "Forbidden" : "Bad Request") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + (contentType.startsWith("text/html") ? "Cache-Control: no-cache, no-store, must-revalidate\r\nPragma: no-cache\r\nExpires: 0\r\n" : "")
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type, Authorization\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void sendByteResponse(OutputStream out, int status, String contentType, byte[] bytes) throws IOException {
        String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : status == 404 ? "Not Found" : "Error") + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Cache-Control: public, max-age=86400\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

            private void sendRedirect(OutputStream out, String location) throws IOException {
        String header = "HTTP/1.1 302 Found\r\n"
                + "Location: " + location + "\r\n"
                + "Cache-Control: public, max-age=3600\r\n"
                + "Content-Length: 0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String loadAssetText(String assetPath) {
        try (InputStream is = appContext.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    private static byte[] loadAssetBytes(String assetPath) {
        try (InputStream is = appContext.getAssets().open(assetPath);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static String getRenderedPwaHtml() {
        String html = loadAssetText("web/index.html");
        if (html.isEmpty()) return "<!doctype html><html><body><h1>Nexus TV Remote</h1></body></html>";
        return html.replace("{{APP_NAME}}", "Nexus TV Remote")
                   .replace("{{BRAND_NAME}}", "NEXUS")
                   .replace("{{BRAND_SUFFIX}}", "TV")
                   .replace("{{BUILD_ID}}", "2026.1");
    }

    private static String getRenderedManifest() {
        String json = loadAssetText("web/manifest.json");
        if (json.isEmpty()) return "{}";
        return json.replace("{{APP_NAME}}", "Nexus TV Remote")
                   .replace("{{SHORT_NAME}}", "Nexus Remote");
    }

    private static String getRenderedSwJs() {
        String js = loadAssetText("web/sw.js");
        if (js.isEmpty()) return "self.addEventListener('install', e => self.skipWaiting());\n";
        return js;
    }

    private static String getRenderedAppJs() {
        return loadAssetText("web/js/app.js");
    }
}
