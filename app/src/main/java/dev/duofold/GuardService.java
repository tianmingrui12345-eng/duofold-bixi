package dev.duofold;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import dev.duofold.bridge.BridgeClient;
import dev.duofold.bridge.IFoldBridge;

/**
 * Keeps this app in the accessibility service list. Shizuku's own pairing service rewrites
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES and, on some MIUI builds, drops the other
 * entries with it — the effect then stops until the service is switched back on by hand.
 *
 * Shell (uid 2000) holds WRITE_SECURE_SETTINGS, so the re-add goes through the capture bridge.
 */
public final class GuardService extends Service {
    private static final long INTERVAL=60_000L;
    private static final int NOTIFICATION_ID=82;
    private final Handler handler=new Handler(Looper.getMainLooper());

    private final Runnable check=new Runnable() {
        @Override public void run() {
            try {
                IFoldBridge bridge=BridgeClient.get(GuardService.this).service();
                if(bridge==null) BridgeClient.get(GuardService.this).connect();
                else bridge.ensureAccessibilityService(component(GuardService.this));
            } catch(Throwable ignored) {}
            handler.postDelayed(this,INTERVAL);
        }
    };

    public static String component(Context context) {
        return new ComponentName(context,FoldService.class).flattenToString();
    }

    public static void start(Context context) {
        try {context.startForegroundService(new Intent(context,GuardService.class));}
        catch(Throwable error) {android.util.Log.w("DuoFold","guard start failed",error);}
    }

    public static void stop(Context context) {
        try {context.stopService(new Intent(context,GuardService.class));}
        catch(Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel=new NotificationChannel("duo_guard",UiText.t("无障碍守护","Accessibility guard"),
                NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        Notification notification=new Notification.Builder(this,"duo_guard")
                .setSmallIcon(R.drawable.ic_fold)
                .setContentTitle(UiText.t("保持无障碍服务开启","Keeping the accessibility service on"))
                .setOngoing(true).build();
        startForeground(NOTIFICATION_ID,notification);
        handler.post(check);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId) {return START_STICKY;}
    @Override public void onDestroy() {handler.removeCallbacks(check);super.onDestroy();}
    @Override public IBinder onBind(Intent intent) {return null;}
}
