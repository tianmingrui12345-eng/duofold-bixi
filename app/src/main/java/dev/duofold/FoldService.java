package dev.duofold;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import dev.duofold.bridge.BridgeClient;
import dev.duofold.bridge.ICaptureListener;
import dev.duofold.bridge.IFoldBridge;
import dev.duofold.motion.FoldModel;
import dev.duofold.motion.EffectStyle;
import dev.duofold.motion.HingeTiltSensor;
import dev.duofold.render.FoldView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.duofold.UiText.t;

public final class FoldService extends AccessibilityService {
    private static volatile FoldService instance;
    public static FoldService current() { return instance; }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService touches=Executors.newSingleThreadExecutor();
    private final AtomicInteger queuedTouches=new AtomicInteger();
    private BridgeClient client;
    private FoldView view;
    private HingeTiltSensor sensor;
    private WindowManager wm;
    private boolean active,captureReady,intercepting;
    private volatile int generation;
    private long lastFrame,started,frames;
    private float progress=1,intensity=1;
    private String status=t("尚未开启","Not started");
    private SessionRecovery recovery;
    private boolean connected,receiverRegistered;
    private final Runnable resumeSession=()-> {
        if(!connected || recovery==null || !recovery.pending() || active) return;
        if(!screenUsable()) {
            // SCREEN_ON may arrive while the keyguard is still dismissing; USER_PRESENT
            // is not delivered on every OEM/no-lock configuration.
            if(getSystemService(PowerManager.class).isInteractive()) main.postDelayed(this.resumeSession,1000);
            return;
        }
        if(client.service()==null) {client.connect();return;}
        if(recovery.canResume(active,true,false,true)) {
            recovery.beginResume();
            startSessionInternal();
        }
    };
    private final Runnable bridgeChanged=()-> {
        if(!connected) return;
        if(active && client.service()==null) {
            if(recovery.requested()) suspendSession(t("等待 Shizuku 重连后恢复","Waiting for Shizuku to reconnect"));
            else stopSession(t("Shizuku 断开，已恢复原始屏幕","Shizuku disconnected; original screen restored"));
        }
        if(client.service()!=null) scheduleResume();
    };
    private final BroadcastReceiver screenState=new BroadcastReceiver() {
        @Override public void onReceive(Context context,Intent intent) {
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                if(active || recovery.pending()) suspendSession(t("屏幕已关闭，解锁后自动恢复","Screen off; resumes after unlock"));
            } else if(Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                    || Intent.ACTION_USER_PRESENT.equals(intent.getAction())) scheduleResume();
        }
    };
    @Override protected void onServiceConnected() {
        if(connected) {scheduleResume();return;}
        wm=getSystemService(WindowManager.class);client=BridgeClient.get(this);
        // Reload on every rebind: a stop notification may have cancelled intent while unbound.
        recovery=new SessionRecovery(getSharedPreferences("duo",0).getBoolean("resumeGlobalEffect",false));
        connected=true;instance=this;
        try {registerComponentCallbacks(configWatcher);} catch(RuntimeException ignored){}
        client.addObserver(bridgeChanged);client.connect();setIntercept(false);
        if(!receiverRegistered) {
            IntentFilter filter=new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if(Build.VERSION.SDK_INT>=33)
                registerReceiver(screenState,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(screenState,filter);
            receiverRegistered=true;
        }
        scheduleResume();
    }
    private boolean screenUsable() {
        PowerManager power=getSystemService(PowerManager.class);
        KeyguardManager keyguard=getSystemService(KeyguardManager.class);
        // The lock screen is deliberately allowed: the shell-side capture can still read it, so
        // the effect works there too. Only a switched-off screen stops the session.
        return power!=null && power.isInteractive();
    }
    private void scheduleResume() {
        if(connected && recovery!=null && recovery.pending() && !main.hasCallbacks(resumeSession))
            main.postDelayed(resumeSession,600);
    }
    private void saveRecovery() {
        getSharedPreferences("duo",0).edit().putBoolean("resumeGlobalEffect",recovery.requested()).apply();
    }
    private void suspendSession(String reason) {
        recovery.suspend();saveRecovery();
        if(!recovery.requested()) reason=t("屏幕已关闭，已恢复原始屏幕","Screen off; original screen restored");
        releaseSession(reason);
        scheduleResume();
    }
    private void sessionFailed(String reason) {
        // Capture/heartbeat errors can arrive before SCREEN_OFF on vendor ROMs.
        if(recovery!=null && recovery.requested() && (!screenUsable() || recovery.canRetry())) suspendSession(reason);
        else stopSession(reason);
    }
    public boolean isActive() { return active; }
    public boolean isResumePending() {return recovery!=null && recovery.pending();}
    public String status() { return status; }
    public float progress() { return progress; }
    public long frames() { return frames; }
    public String diagnostics() { return "active="+active+" ready="+captureReady+" intercept="+intercepting
            +" size="+(view==null?"none":view.getWidth()+"x"+view.getHeight())+" progress="+progress
            +" frames="+frames+" touches="+touchCount+" resume="+(recovery!=null && recovery.pending())
            +" attempts="+(recovery==null?0:recovery.attempts())+" status="+status; }
    private long touchCount;
    private boolean discardGesture;
    public void setEffectStyle(EffectStyle style) {if(view!=null)view.setEffectStyle(style);}
    public void startSession() {
        if(!connected || active) return;
        main.removeCallbacks(resumeSession);
        recovery.userStart();saveRecovery();
        startSessionInternal();
    }
    private void startSessionInternal() {
        if(!screenUsable()) {
            suspendSession(t("等待亮屏并解锁后恢复","Waiting for screen on and unlock"));return;
        }
        IFoldBridge bridge=client.service();
        if(bridge==null) {recovery.stop();saveRecovery();status=t("先完成 Shizuku 授权","Finish Shizuku authorization first");Toast.makeText(this,status,Toast.LENGTH_LONG).show();return;}
        if(getSystemService(android.view.accessibility.AccessibilityManager.class).isTouchExplorationEnabled()) {
            recovery.stop();saveRecovery();
            status=t("当前有触摸探索服务运行，请先关闭以避免手势冲突","A touch exploration service is running; turn it off first to avoid gesture conflicts");
            Toast.makeText(this,status,Toast.LENGTH_LONG).show();return;
        }
        active=true;captureReady=false;generation++;lastFrame=0;frames=0;
        touchCount=0;
        AppFilter.load(this);
        started=SystemClock.uptimeMillis();
        intensity=getSharedPreferences("duo",0).getFloat("intensity",1);
        dev.duofold.EffectPrefs.load(this);
        float range=getSharedPreferences("duo",0).getFloat("range",80);
        boolean includeZ=getSharedPreferences("duo",0).getBoolean("zAxis",false);
        status=t("正在取得真实屏幕画面","Capturing the real screen");
        try {
            layerWanted=false;
            // The hinge is the only pose source.
            dev.duofold.motion.HingeTiltSensor.Listener pose=(p,forwardDegrees,sideDegrees)-> {
                progress=p;
                // The ray-projection model saturates at 80 degrees of tilt (FoldModel clamps
                // there). Map the chosen preset onto that ceiling, otherwise a 90 degree
                // preset hit the ceiling at half the real fold and the animation froze.
                float ceiling=80f;
                float scale=ceiling/Math.max(15f,dev.duofold.EffectPrefs.range);
                float unfolded=Math.max(0f,forwardDegrees-FLAT_OFFSET_DEGREES);
                float effective=Math.min(ceiling,unfolded*scale);
                // The layer's on/off decision must follow the real hinge angle: using the
                // scaled value kept the layer attached (and the screen untouchable) whenever
                // the max-tilt preset was small.
                lastForward=unfolded;
                if(view!=null && active) view.setFold(
                        (float)Math.toRadians(effective),(float)Math.toRadians(sideDegrees),
                        dev.duofold.EffectPrefs.intensity);
                syncLayer();
            };
            sensor=new HingeTiltSensor(this,pose);
            if(!sensor.start()) throw new IllegalStateException(t("没有可用的铰链传感器","No usable hinge sensor"));
            // Nothing is drawn while the panel is flat: the layer is created on the first fold.
            syncLayer();
            showNotification();main.post(watchdog);
        } catch(Exception e) {sessionFailed(e.toString());}
    }
    private SurfaceControl glassLayer;
    private SurfaceControlViewHost glassHost;
    private Surface captureSurface;
    private int captureWidth,captureHeight,captureRetries;
    /**
     * Hysteresis for the layer: attach once the panel is folded by 2 degrees, release again
     * below 1. The hinge reports whole degrees with ~1 degree of bias when flat, so a single
     * threshold would flap and toggle touch on and off while resting.
     */
    private static final float ATTACH_DEGREES=0.5f;
    private static final float RELEASE_DEGREES=0.15f;
    /**
     * The hinge reads 0-180 with ~1 degree of bias and quantises to whole degrees, so the
     * first 2 degrees are treated as flat. Everything is measured from there.
     */
    private static final float FLAT_OFFSET_DEGREES=2f;
    private volatile float lastForward;
    private volatile boolean layerWanted;
    /** Package currently in front; drives the per-app allow list. */
    private volatile String foregroundPkg;
    /** When the layer was last attached: guards against attach/release flapping. */
    private long attachedAt;

    /**
     * The display-attached layer is a full-screen window and steals every touch, so it only
     * exists while the phone is actually folded. Fully unfolded means no layer at all, which
     * gives the panel back its normal input handling.
     */
    /**
     * bixi: inner panel 1224x2912 (aspect 2.38), cover panel 1208x1392 (aspect 1.15).
     * The effect only makes sense on the big inner screen, so never attach it to the cover.
     */
    private boolean innerDisplayActive() {
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int longSide=Math.max(metrics.widthPixels,metrics.heightPixels);
        int shortSide=Math.min(metrics.widthPixels,metrics.heightPixels);
        return shortSide>0 && longSide/(float)shortSide>1.9f;
    }
    private final android.content.ComponentCallbacks configWatcher=new android.content.ComponentCallbacks() {
        @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
            main.post(()->rebuildLayer());
        }
        @Override public void onLowMemory() {}
    };
    /**
     * Toggling dark mode (or changing font scale / rotation) re-creates the windows, and the
     * hosted layer survives as a window that no longer draws anything but still takes the
     * whole screen's input. Tear it down and let syncLayer() build a fresh one.
     */
    private void rebuildLayer() {
        if(!active) return;
        boolean wanted=layerWanted;
        layerWanted=false;
        stopCapture();
        if(view!=null) {FoldView old=view;view=null;old.releaseResources();old.onPause();}
        releaseGlassHost();
        if(wanted) syncLayer();
    }
    private void syncLayer() {
        if(!active) return;
        long now=SystemClock.uptimeMillis();
        // Use the angle that is actually on screen, not the raw sensor one: while unfolding the
        // raw value reaches the release threshold while the frame is still visibly bent, and
        // dropping the layer then made the last few degrees snap flat in a single frame.
        float shown=view!=null?view.smoothedDegrees():lastForward;
        boolean folded=layerWanted
                ? (shown>RELEASE_DEGREES || lastForward>RELEASE_DEGREES || now-attachedAt<600)
                : (shown>ATTACH_DEGREES && lastForward>ATTACH_DEGREES);
        boolean want=folded && innerDisplayActive() && AppFilter.allows(foregroundPkg);
        if(want!=layerWanted) android.util.Log.i("DuoFold","sync want="+want
                +" forward="+lastForward+" inner="+innerDisplayActive()+" host="+(glassHost!=null));
        if(want) {
            if(!layerWanted) attachedAt=now;
            layerWanted=true;
            if(glassHost==null) addOverlay();
            return;
        }
        layerWanted=false;
        if(glassHost==null && view==null) return;
        {
            stopCapture();
            if(view!=null) {
                FoldView old=view;view=null;
                old.releaseResources();old.onPause();
            }
            releaseGlassHost();
            status=t("已展开，触屏恢复正常；折起来才会出现效果","Flat: touch restored; fold to see the effect");
        }
    }
    private void restartIfStalled() {
        if(!active || !layerWanted) return;
        if(SystemClock.uptimeMillis()-lastFrame<700) return;
        lastFrame=0;started=SystemClock.uptimeMillis();
        boolean rebuild=view==null || glassHost==null || captureSurface==null || !captureSurface.isValid();
        android.util.Log.i("DuoFold","app switch: rebuild="+rebuild);
        if(rebuild) {layerWanted=false;syncLayer();}
        else beginCapture(captureSurface,captureWidth,captureHeight);
    }
    private void stopCapture() {
        captureReady=false;
        try {IFoldBridge bridge=client.service();if(bridge!=null)bridge.stop();}catch(Exception ignored){}
    }
    private void addOverlay() {
        int overlayGeneration=generation;
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        final int w=metrics.widthPixels,h=metrics.heightPixels;
        try {
            Display display=wm.getDefaultDisplay();
            glassHost=new SurfaceControlViewHost(this,display,new android.os.Binder());
            view=new FoldView(this,new FoldView.Listener() {
                @Override public void onReady(Surface surface,int cw,int ch) {
                    captureSurface=surface;captureWidth=cw;captureHeight=ch;
                    if(active && overlayGeneration==generation) beginCapture(surface,cw,ch);
                }
                @Override public void onFirstFrame() {}
                @Override public void onError(String message) {
                    if(overlayGeneration==generation) sessionFailed(message);
                }
            });
            view.setEffectStyle(EffectStyle.fromId(getSharedPreferences("duo",0).getString("effectStyle","classic")));
            glassHost.setView(view,w,h);
            SurfaceControlViewHost.SurfacePackage packageInfo=glassHost.getSurfacePackage();
            if(packageInfo==null) throw new IllegalStateException("no surface package");
            SurfaceControl control=packageInfo.getSurfaceControl();
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                transaction.setScale(control,1f,1f).setPosition(control,0,0)
                        // Layer 1 sits under app windows (which live around 21000), so the
                        // effect vanished as soon as an app came to the foreground.
                        .setLayer(control,1000000).setAlpha(control,1f).setVisibility(control,true);
                // Must be flagged before the display attach, or WindowManager has already
                // registered it as a touchable window and the whole screen stops responding.
                transaction.apply();
            }
            // Hosted layers composite on this ROM only through the accessibility display hook.
            java.lang.reflect.Method attach=AccessibilityService.class
                    .getMethod("attachAccessibilityOverlayToDisplay",int.class,SurfaceControl.class);
            attach.invoke(this,display.getDisplayId(),control);
            glassLayer=control;
            try {
                IFoldBridge bridge=client.service();
                if(bridge!=null) bridge.makeGlassPassThrough(control);
            } catch(Exception error) {android.util.Log.w("DuoFold","pass-through",error);}
            android.util.Log.i("DuoFold","glass host attached "+w+"x"+h);
        } catch(Throwable error) {
            android.util.Log.e("DuoFold","glass host",error);
            sessionFailed(t("无法创建显示图层","Could not create the display layer"));
        }
    }
    private void releaseGlassHost() {
        if(glassHost!=null) {
            try{glassHost.release();}catch(Throwable error){android.util.Log.w("DuoFold","release host",error);}
            glassHost=null;
        }
        glassLayer=null;
    }
    private void beginCapture(Surface surface,int w,int h) {
        if(!active || view==null) return;
        setIntercept(false);captureReady=false;started=SystemClock.uptimeMillis();lastFrame=0;
        SurfaceControl exclude=glassLayer;
        android.util.Log.i("DuoFold","capture start exclude="+(exclude==null?"null":("valid="+exclude.isValid())));
        Bundle options=new Bundle();if(exclude!=null)options.putParcelable("exclude",exclude);
        options.putInt("width",w);options.putInt("height",h);options.putInt("fps",120);
        options.putInt("displayWidth",view.getWidth());options.putInt("displayHeight",view.getHeight());
        options.putInt("rotation",wm.getDefaultDisplay()==null?Surface.ROTATION_0:wm.getDefaultDisplay().getRotation());
        int session=generation;
        io.execute(()-> {
            try {
                IFoldBridge bridge=client.service();
                if(bridge==null || session!=generation) return;
                bridge.start(surface,options,new ICaptureListener.Stub() {
                    @Override public void onFrame(long sequence,long timestampMs) {
                        main.post(()-> {
                            if(!active || session!=generation) return;
                            lastFrame=SystemClock.uptimeMillis();frames=sequence;captureRetries=0;
                            if(Build.VERSION.SDK_INT>=31 && !captureReady) {
                                captureReady=true;
                                if(!screenUsable()) {suspendSession(t("等待解锁","Waiting for unlock"));return;}
                                try {setIntercept(true);recovery.recovered();status=runningStatus();}
                                catch(RuntimeException e) {sessionFailed(e.toString());}
                            }
                        });
                    }
                    @Override public void onError(String message) {main.post(()->{if(session==generation) sessionFailed(message);});}
                });
            } catch(Exception e) {main.post(()->{if(session==generation) sessionFailed(e.toString());});}
        });
    }
    private final Runnable watchdog=new Runnable() {
        @Override public void run() {
            if(!active) return;
            if(!screenUsable()) {suspendSession(t("屏幕已锁定，解锁后自动恢复","Screen locked; resumes after unlock"));return;}
            long now=SystemClock.uptimeMillis();
            boolean liveCapture=Build.VERSION.SDK_INT>=31;
            if(layerWanted && ((liveCapture && lastFrame>0 && now-lastFrame>1500) || (lastFrame==0 && now-started>5000))) {
                // Switching to another app (or the panel changing) can drop the capture while
                // the layer is still up. Restart it instead of tearing the session down.
                if(captureRetries<40) {
                    captureRetries++;
                    // A configuration change (or the panel switch that happens when another
                    // app comes up) can leave the hosted view gone; then the capture has no
                    // target and must be rebuilt from scratch, not restarted.
                    boolean rebuild=view==null || glassHost==null
                            || captureSurface==null || !captureSurface.isValid();
                    android.util.Log.i("DuoFold","capture stalled, rebuild="+rebuild+" #"+captureRetries);
                    lastFrame=0;started=now;
                    if(rebuild) {layerWanted=false;syncLayer();}
                    else beginCapture(captureSurface,captureWidth,captureHeight);
                } else {
                    sessionFailed(t("画面更新超时，已恢复原始屏幕","Frame update timed out; original screen restored"));return;
                }
            }
            syncLayer();
            IFoldBridge bridge=client.service();
            if(bridge==null) {bridgeChanged.run();return;}
            int session=generation;
            io.execute(()->{try{if(session==generation)bridge.heartbeat();}catch(Exception e){main.post(()-> {
                if(active && session==generation) sessionFailed(t("心跳中断","Heartbeat lost"));
            });}});
            main.postDelayed(this,500);
        }
    };
    @Override public void onMotionEvent(MotionEvent event) {
        touchCount++;
        if(!active || !captureReady || view==null) return;
        if(event.getPointerCount()>=3) {stopSession(t("三指退出，已恢复原始屏幕","Three-finger exit; original screen restored"));return;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN) discardGesture=false;
        int n=event.getPointerCount();
        MotionEvent.PointerProperties[] props=new MotionEvent.PointerProperties[n];
        MotionEvent.PointerCoords[] coords=new MotionEvent.PointerCoords[n];
        FoldModel.State state=view.inputState();
        float width=Math.max(1,view.getWidth()),height=Math.max(1,view.getHeight());
        for(int i=0;i<n;i++) {
            props[i]=new MotionEvent.PointerProperties();event.getPointerProperties(i,props[i]);
            coords[i]=new MotionEvent.PointerCoords();event.getPointerCoords(i,coords[i]);
            float[] uv=FoldModel.sourceAt(coords[i].x/width,coords[i].y/height,state);
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN &&
                    (uv[0]<0 || uv[0]>1 || uv[1]<0 || uv[1]>1)) {
                discardGesture=true;return;
            }
            coords[i].x=FoldModel.clamp(uv[0])*width;coords[i].y=FoldModel.clamp(uv[1])*height;
        }
        MotionEvent mapped=MotionEvent.obtain(event.getDownTime(),event.getEventTime(),event.getAction(),n,props,coords,
                event.getMetaState(),event.getButtonState(),event.getXPrecision(),event.getYPrecision(),
                event.getDeviceId(),event.getEdgeFlags(),InputDevice.SOURCE_TOUCHSCREEN,event.getFlags());
        if(discardGesture) {mapped.recycle();return;}
        if(queuedTouches.incrementAndGet()>16) {
            queuedTouches.decrementAndGet();mapped.recycle();stopSession(t("触控队列超时，已恢复原始屏幕","Touch queue timed out; original screen restored"));return;
        }
        int session=generation;
        touches.execute(()-> {
            try {
                IFoldBridge bridge=client.service();
                if(session==generation && (bridge==null || !bridge.inject(mapped)))
                    main.post(()->{if(session==generation) stopSession(t("系统拒绝触控注入；检查 USB 调试（安全设置）","System rejected touch injection; check USB debugging (security settings)"));});
            } catch(Exception e) {main.post(()->{if(session==generation)stopSession(t("触控连接中断","Touch connection lost"));});}
            finally {mapped.recycle();queuedTouches.decrementAndGet();}
        });
    }
    private void setIntercept(boolean enabled) {
        // The ported overlay is purely visual. Intercepting motion events here means the
        // whole screen stops responding unless every event is re-injected, so never do it.
        // Always clear the flag: a service that was switched on in an earlier session keeps
        // intercepting every touch until motionEventSources is explicitly reset to zero.
        intercepting=false;
        if(Build.VERSION.SDK_INT<34) return;
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null) return;
        info.setMotionEventSources(0);
        setServiceInfo(info);
    }
    public void stopSession(String reason) {
        if(recovery!=null) {recovery.stop();saveRecovery();}
        releaseSession(reason);
    }
    private void releaseSession(String reason) {
        main.removeCallbacks(resumeSession);
        // Release original input before doing any binder work or GPU cleanup.
        try {setIntercept(false);} catch(RuntimeException e) {android.util.Log.w("DuoFold","Cannot reset input on disconnected service",e);}
        active=false;captureReady=false;generation++;status=reason;
        android.util.Log.i("DuoFold", "Stopped: "+reason+"; frames="+frames+" touches="+touchCount);
        main.removeCallbacks(watchdog);
        if(sensor!=null) {sensor.stop();sensor=null;}
        if(view!=null) {
            FoldView old=view;view=null;
            old.releaseResources();old.onPause();
            releaseGlassHost();
        }
        IFoldBridge bridge=client==null?null:client.service();
        if(bridge!=null) io.execute(()-> {
            // An old service's teardown must not stop a newly bound service's capture.
            if(instance!=null && instance!=FoldService.this) return;
            try{bridge.stop();}catch(Exception ignored){}
        });
        getSystemService(NotificationManager.class).cancel(81);
        getSharedPreferences("duo",0).edit().putString("lastStatus",reason).apply();
    }
    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if(active) {
            setIntercept(false);captureReady=false;lastFrame=0;started=SystemClock.uptimeMillis();generation++;
            FoldView old=view;view=null;
            if(old!=null) {
                old.releaseResources();old.onPause();
                releaseGlassHost();
            }
            IFoldBridge bridge=client==null?null:client.service();
            if(bridge!=null)io.execute(()->{try{bridge.stop();}catch(Exception ignored){}});
            try {addOverlay();status=t("屏幕方向已切换，正在重新校准","Orientation changed; recalibrating");}
            catch(Exception e){stopSession(t("横竖屏切换失败：","Orientation switch failed: ")+e.getMessage());}
        }
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(active && !screenUsable()) suspendSession(t("屏幕已锁定，解锁后自动恢复","Screen locked; resumes after unlock"));
        // Switching to another app tears the Shizuku capture down while the layer stays up,
        // so the effect silently disappeared in every third-party app. Re-arm it here.
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg=event.getPackageName();
            String name=pkg==null?null:pkg.toString();
            if(active && name!=null && !name.equals(foregroundPkg)) {
                foregroundPkg=name;
                main.post(this::syncLayer);
            }
            android.util.Log.i("DuoFold","foreground="+pkg+" active="+active+" want="+layerWanted
                    +" host="+(glassHost!=null)+" frames="+frames);
            if(active && layerWanted) main.postDelayed(this::restartIfStalled,600);
        }
        else scheduleResume();
    }
    @Override public void onInterrupt() {sessionFailed(t("辅助服务被中断","Accessibility service interrupted"));}
    private void disconnect() {
        if(!connected && recovery!=null) return;
        connected=false;
        if(instance==this) instance=null;
        if(recovery!=null) {
            // A system rebind is different from the user disabling this service in Settings.
            String enabled=android.provider.Settings.Secure.getString(getContentResolver(),
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String component=new android.content.ComponentName(this,FoldService.class).flattenToString();
            boolean stillEnabled=false;
            if(enabled!=null) for(String name:enabled.split(":"))
                if(component.equalsIgnoreCase(name) || new android.content.ComponentName(this,FoldService.class)
                        .flattenToShortString().equalsIgnoreCase(name)) stillEnabled=true;
            if(stillEnabled) recovery.suspend();else recovery.stop();
            saveRecovery();
        }
        releaseSession(t("辅助服务已断开，等待系统重新连接","Accessibility service disconnected; waiting for system rebind"));
        if(client!=null)client.removeObserver(bridgeChanged);
        if(receiverRegistered) {try{unregisterReceiver(screenState);}catch(Exception ignored){}receiverRegistered=false;}
    }
    @Override public boolean onUnbind(Intent intent) {disconnect();return super.onUnbind(intent);}
    @Override public void onDestroy() {
        disconnect();
        try {unregisterComponentCallbacks(configWatcher);} catch(RuntimeException ignored){}
        io.shutdown();touches.shutdown();super.onDestroy();
    }
    private void showNotification() {
        NotificationManager nm=getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("duo_live",t("展开效果","Fold effect"),NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop=PendingIntent.getBroadcast(this,1,new Intent(this,StopReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=new Notification.Builder(this,"duo_live").setSmallIcon(R.drawable.ic_fold)
                .setContentTitle(t("Duo Fold 正在运行","Duo Fold is running"))
                .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,t("停止","Stop"),stop).build()).build();
        nm.notify(81,notification);
    }
    private String runningStatus() {
        return Build.VERSION.SDK_INT>=34?t("运行中 · 三指同时触屏可退出","Running · three-finger touch to exit"):t("运行中 · 使用通知栏按钮退出","Running · use the notification button to exit");
    }
}
