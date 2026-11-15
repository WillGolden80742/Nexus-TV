package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    public static final class NotificationItem {
        public final String key;
        public final String pkg;
        public final String appName;
        public final String title;
        public final String text;
        public final long time;
        public final android.app.PendingIntent intent;
        public final boolean clearable;
        public Bitmap icon;

        public NotificationItem(String key, String pkg, String appName, String title, String text,
                                long time, android.app.PendingIntent intent, boolean clearable, Bitmap icon) {
            this.key = key;
            this.pkg = pkg;
            this.appName = appName;
            this.title = title;
            this.text = text;
            this.time = time;
            this.intent = intent;
            this.clearable = clearable;
            this.icon = icon;
        }
    }

    public static final List<NotificationItem> activeNotifications = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static volatile Runnable onNotificationsChanged;
    private static NowPlaying instance;

    public static NowPlaying getInstance() {
        return instance;
    }

    public static int notificationCount() {
        return activeNotifications.size();
    }

    public static void dismissNotification(String key) {
        if (instance != null) {
            try {
                instance.cancelNotification(key);
            } catch (Throwable ignored) {}
        }
        for (NotificationItem item : activeNotifications) {
            if (item.key.equals(key)) {
                activeNotifications.remove(item);
                break;
            }
        }
        notifyChanged();
    }

    public static void dismissAllNotifications() {
        if (instance != null) {
            try {
                instance.cancelAllNotifications();
            } catch (Throwable ignored) {}
        }
        activeNotifications.clear();
        notifyChanged();
    }

    private static void notifyChanged() {
        if (onNotificationsChanged != null) {
            new Handler(Looper.getMainLooper()).post(onNotificationsChanged);
        }
        RemoteServer.broadcastNotifications();
    }

    @Override public void onListenerConnected() {
        instance = this;
        refreshNotifications();
        RemoteServer.start(this);
    }

    @Override public void onListenerDisconnected() {
        if (instance == this) instance = null;
    }

    @Override public void onNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        handleNotificationPosted(sbn);
    }

    @Override public void onNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        handleNotificationRemoved(sbn);
    }

    private void handleNotificationPosted(android.service.notification.StatusBarNotification sbn) {
        if (sbn == null || sbn.getPackageName() == null) return;
        if ("io.freemytv".equals(sbn.getPackageName())) return;
        android.app.Notification n = sbn.getNotification();
        if (n == null) return;
        android.os.Bundle extras = n.extras;
        CharSequence title = extras != null ? extras.getCharSequence(android.app.Notification.EXTRA_TITLE) : null;
        CharSequence text = extras != null ? extras.getCharSequence(android.app.Notification.EXTRA_TEXT) : null;
        if (TextUtils.isEmpty(title) && TextUtils.isEmpty(text)) return;

        PackageManager pm = getPackageManager();
        String appName = sbn.getPackageName();
        try {
            appName = pm.getApplicationLabel(pm.getApplicationInfo(sbn.getPackageName(), 0)).toString();
        } catch (Throwable ignored) {}

        Bitmap iconBmp = null;
        try {
            if (extras != null && extras.containsKey(android.app.Notification.EXTRA_LARGE_ICON)) {
                Object iconObj = extras.get(android.app.Notification.EXTRA_LARGE_ICON);
                if (iconObj instanceof Bitmap) iconBmp = (Bitmap) iconObj;
            }
        } catch (Throwable ignored) {}

        NotificationItem item = new NotificationItem(
                sbn.getKey(),
                sbn.getPackageName(),
                appName,
                title != null ? title.toString().trim() : "",
                text != null ? text.toString().trim() : "",
                sbn.getPostTime(),
                n.contentIntent,
                sbn.isClearable(),
                iconBmp
        );

        for (int i = 0; i < activeNotifications.size(); i++) {
            if (activeNotifications.get(i).key.equals(sbn.getKey())) {
                activeNotifications.set(i, item);
                notifyChanged();
                return;
            }
        }
        activeNotifications.add(0, item);
        notifyChanged();
    }

    private void handleNotificationRemoved(android.service.notification.StatusBarNotification sbn) {
        if (sbn == null) return;
        for (NotificationItem it : activeNotifications) {
            if (it.key.equals(sbn.getKey())) {
                activeNotifications.remove(it);
                notifyChanged();
                break;
            }
        }
    }

    public void refreshNotifications() {
        try {
            android.service.notification.StatusBarNotification[] sbns = getActiveNotifications();
            activeNotifications.clear();
            if (sbns != null) {
                for (android.service.notification.StatusBarNotification sbn : sbns) {
                    handleNotificationPosted(sbn);
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void ensureFakeNotifications() {
        // No fake notifications: only real system notifications from Android NotificationListenerService
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;
    public static volatile Watch activeWatch;

    public static String getActiveTrack() {
        return activeWatch != null ? activeWatch.title : null;
    }

    public static String getActiveArtist() {
        return activeWatch != null ? activeWatch.artist : null;
    }

    public static boolean isMediaPlaying() {
        return activeWatch != null && activeWatch.playing;
    }

    public static String getActivePkg() {
        return activeWatch != null ? activeWatch.pkg : null;
    }

    public static Bitmap getActiveArt() {
        return activeWatch != null ? activeWatch.icon : null;
    }

    public static String getActiveArtUrl() {
        return activeWatch != null ? activeWatch.artUrl : null;
    }

    public static long getActivePositionMs() {
        return activeWatch != null ? activeWatch.getCurrentPositionMs() : 0;
    }

    public static long getActiveDurationMs() {
        return activeWatch != null ? activeWatch.getDurationMs() : 0;
    }

    public static void sendMediaButtonSystem(int keyCode) {
        Context ctx = activeWatch != null ? activeWatch.context : RemoteServer.appContext;
        if (ctx == null) return;
        // 1. Android OS AudioManager dispatch (delivers directly to the system-focused audio player)
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
                return;
            }
        } catch (Throwable ignored) {}

        // 2. Fallback only if AudioManager dispatch failed
        try {
            Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
            it.setPackage("com.spotify.tv.android");
            it.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            ctx.sendBroadcast(it);
            it.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            ctx.sendBroadcast(it);
        } catch (Throwable ignored) {}
    }

    public static void pause() {
        if (activeWatch != null && activeWatch.activeController != null) {
            try {
                activeWatch.activeController.getTransportControls().pause();
                return;
            } catch (Throwable ignored) {}
        }
        sendMediaButtonSystem(KeyEvent.KEYCODE_MEDIA_PAUSE);
    }

    public static void play() {
        if (activeWatch != null && activeWatch.activeController != null) {
            try {
                activeWatch.activeController.getTransportControls().play();
                return;
            } catch (Throwable ignored) {}
        }
        sendMediaButtonSystem(KeyEvent.KEYCODE_MEDIA_PLAY);
    }

    public static void playPause() {
        if (activeWatch != null && activeWatch.activeController != null) {
            try {
                PlaybackState state = activeWatch.activeController.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    activeWatch.activeController.getTransportControls().pause();
                } else {
                    activeWatch.activeController.getTransportControls().play();
                }
                return;
            } catch (Throwable ignored) {}
        }
        sendMediaButtonSystem(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    public static void skipNext() {
        if (activeWatch != null && activeWatch.activeController != null) {
            try {
                activeWatch.activeController.getTransportControls().skipToNext();
                return;
            } catch (Throwable ignored) {}
        }
        sendMediaButtonSystem(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    public static void skipPrev() {
        if (activeWatch != null && activeWatch.activeController != null) {
            try {
                activeWatch.activeController.getTransportControls().skipToPrevious();
                return;
            } catch (Throwable ignored) {}
        }
        sendMediaButtonSystem(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;
        /** The title alone (the app's package when it gives none): the line's other parts can change every second. */
        volatile String title;
        volatile String artist;
        volatile Bitmap icon;
        volatile String artUrl;
        volatile boolean playing;
        volatile String pkg;
        volatile MediaController activeController;
        volatile long positionMs = 0;
        volatile long durationMs = 0;
        volatile long positionUpdateTime = 0;
        volatile float playbackSpeed = 1f;

        public long getCurrentPositionMs() {
            if (activeController != null) {
                try {
                    PlaybackState s = activeController.getPlaybackState();
                    if (s != null) {
                        long pos = s.getPosition();
                        long updateTime = s.getLastPositionUpdateTime();
                        float speed = s.getPlaybackSpeed();
                        if (s.getState() == PlaybackState.STATE_PLAYING && speed > 0 && updateTime > 0) {
                            long diff = SystemClock.elapsedRealtime() - updateTime;
                            return Math.max(0, pos + (long)(diff * speed));
                        }
                        return Math.max(0, pos);
                    }
                } catch (Throwable ignored) {}
            }
            if (playing && positionUpdateTime > 0 && playbackSpeed > 0) {
                long diff = SystemClock.elapsedRealtime() - positionUpdateTime;
                return Math.max(0, positionMs + (long)(diff * playbackSpeed));
            }
            return Math.max(0, positionMs);
        }

        public long getDurationMs() {
            if (activeController != null) {
                try {
                    MediaMetadata m = activeController.getMetadata();
                    if (m != null) {
                        long dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                        if (dur > 0) return dur;
                    }
                } catch (Throwable ignored) {}
            }
            return durationMs;
        }

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            activeWatch = this;
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private final ExecutorService artLoader = Executors.newSingleThreadExecutor();
        private String lastLoadedArt = null;

        private void loadArtAsync(final Uri uri) {
            if (uri == null) return;
            final String u = uri.toString();
            if (u.equals(lastLoadedArt)) return;
            lastLoadedArt = u;
            artLoader.execute(() -> {
                try {
                    Bitmap bmp = null;
                    if (u.startsWith("http://") || u.startsWith("https://")) {
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.setInstanceFollowRedirects(true);
                        conn.connect();
                        java.io.InputStream in = conn.getInputStream();
                        if (in != null) {
                            bmp = BitmapFactory.decodeStream(in);
                            in.close();
                        }
                        conn.disconnect();
                    } else {
                        java.io.InputStream in = context.getContentResolver().openInputStream(uri);
                        if (in != null) {
                            bmp = BitmapFactory.decodeStream(in);
                            in.close();
                        }
                    }
                    if (bmp != null) {
                        final Bitmap finalBmp = bmp;
                        handler.post(() -> {
                            icon = finalBmp;
                            changed.run();
                            try {
                                RemoteServer.broadcastSpotifyState();
                            } catch (Throwable ignored) {}
                        });
                    }
                } catch (Throwable ignored) {}
            });
        }

        void update() {
            String now = null, nowTitle = null, nowArtist = null, nowPkg = null, nowArtUrl = null;
            Bitmap nowIcon = null;
            boolean isPlaying = false;
            MediaController activeMc = null;
            long nowPos = 0, nowDur = 0, nowUpdate = 0;
            float nowSpeed = 1f;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                boolean isCandidate = (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING);
                if (isCandidate || ((state == PlaybackState.STATE_PAUSED || mc.getMetadata() != null) && activeMc == null)) {
                    if (isCandidate) isPlaying = true;
                    activeMc = mc;
                    nowPkg = mc.getPackageName();
                    now = describe(mc);
                    if (s != null) {
                        nowPos = s.getPosition();
                        nowUpdate = s.getLastPositionUpdateTime();
                        nowSpeed = s.getPlaybackSpeed();
                    }
                    MediaMetadata m = mc.getMetadata();
                    MediaDescription d = m != null ? m.getDescription() : null;

                    String foundArtUrl = null;
                    Bitmap foundArtBmp = null;

                    if (m != null) {
                        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
                        if (t != null) nowTitle = t.toString().trim();

                        CharSequence a = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
                        if (a != null) nowArtist = a.toString().trim();

                        long dMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                        if (dMs > 0) nowDur = dMs;

                        // 1. High-resolution album art bitmap from MediaMetadata
                        foundArtBmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                        if (foundArtBmp == null) foundArtBmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);

                        // 2. High-resolution album art URI
                        foundArtUrl = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                        if (foundArtUrl == null) foundArtUrl = m.getString(MediaMetadata.METADATA_KEY_ART_URI);
                    }

                    if (d != null) {
                        if (nowTitle == null && d.getTitle() != null) nowTitle = d.getTitle().toString().trim();
                        if (nowArtist == null && d.getSubtitle() != null) nowArtist = d.getSubtitle().toString().trim();
                        if (foundArtUrl == null && d.getIconUri() != null) foundArtUrl = d.getIconUri().toString();
                        // Only fallback to d.getIconBitmap() if NO real album art exists
                        if (foundArtBmp == null && foundArtUrl == null) foundArtBmp = d.getIconBitmap();
                    }

                    nowArtUrl = foundArtUrl;
                    if (foundArtBmp != null) {
                        nowIcon = foundArtBmp;
                    } else if (foundArtUrl != null) {
                        loadArtAsync(Uri.parse(foundArtUrl));
                    }

                    if (nowTitle == null) nowTitle = mc.getPackageName();
                    if (isCandidate) break;
                }
            }
            if (nowTitle != null && !nowTitle.equals(title) && nowIcon == null) {
                icon = null;
            } else if (nowIcon != null) {
                icon = nowIcon;
            }
            title = nowTitle;
            artist = nowArtist;
            artUrl = nowArtUrl;
            playing = isPlaying;
            pkg = nowPkg;
            activeController = activeMc;
            positionMs = nowPos;
            durationMs = nowDur;
            positionUpdateTime = nowUpdate > 0 ? nowUpdate : SystemClock.elapsedRealtime();
            playbackSpeed = nowSpeed;
            text = now;
            changed.run();
            try {
                RemoteServer.broadcastSpotifyState();
            } catch (Throwable ignored) {}
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
