package dev.duofold;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
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
import dev.duofold.motion.EffectStyle;
import dev.duofold.motion.HingeTiltSensor;
import dev.duofold.render.FoldView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static dev.duofold.UiText.t;

public final class FoldService extends AccessibilityService {
    private static volatile FoldService instance;
    public static FoldService current() { return instance; }
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private BridgeClient client;
    private FoldView view;
    private HingeTiltSensor sensor;
    private WindowManager wm;
    private boolean active,captureReady;
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
        client.addObserver(bridgeChanged);client.connect();clearMotionSources();
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
    /**
     * A capture that returns no buffer once is usually the panel reconfiguring while the fold
     * starts. Retry a few times before giving up, and log the angle so the cause is visible.
     */
    private void captureRefused(String reason) {
        if(captureRetries<3) {
            captureRetries++;
            android.util.Log.w("DuoFold","capture refused at "+lastForward+" deg, retry #"+captureRetries);
            main.postDelayed(this::recoverCapture,400);
            return;
        }
        sessionFailed(reason);
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
        GuardService.start(this);
        AppFilter.load(this);
        foregroundPkg=resolveForeground();
        started=SystemClock.uptimeMillis();
        intensity=getSharedPreferences("duo",0).getFloat("intensity",1);
        dev.duofold.EffectPrefs.load(this);
        float range=getSharedPreferences("duo",0).getFloat("range",80);
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
                // Past 60 degrees of physical fold the effect closes the remaining gap faster,
                // so the last part of the fold is not dead travel. Nothing below the knee changes.
                float knee=dev.duofold.EffectPrefs.tailKnee;
                float boost=dev.duofold.EffectPrefs.tailBoost;
                if(boost>0.001f && unfolded>knee) {
                    float tail=Math.min(1f,(unfolded-knee)/(180f-knee));
                    float ease=1f-(1f-tail)*(1f-tail);
                    effective=Math.min(ceiling,effective+(ceiling-effective)*ease*boost);
                }
                // The layer's on/off decision must follow the real hinge angle: using the
                // scaled value kept the layer attached (and the screen untouchable) whenever
                // the max-tilt preset was small.
                if(unfolded<lastForward-0.05f && layerWanted && unfoldStartedAt==0)
                    unfoldStartedAt=SystemClock.uptimeMillis();
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
            prepareLayer();
        } catch(Exception e) {sessionFailed(e.toString());}
    }
    private SurfaceControl glassLayer;
    private SurfaceControlViewHost glassHost;
    private Surface captureSurface;
    private int captureWidth,captureHeight,captureRetries;
    private boolean captureStarting;
    /**
     * Hysteresis for the layer: attach once the panel is folded by 2 degrees, release again
     * below 1. The hinge reports whole degrees with ~1 degree of bias when flat, so a single
     * threshold would flap and toggle touch on and off while resting.
     */
    // Released a couple of degrees early: at that angle the effect is already invisible
    // (blur ramp ~0.1), and the input system needs a moment to route touches back to the app.
    // Attaching slightly later keeps the hysteresis one-directional, so no flapping.
    // The effect is already invisible below ~5 degrees (blur radius under 1 pt), so the layer is
    // dropped there instead of at the flat position. That hands input back while the hinge is
    // still moving, which is what makes the end of the unfold feel attached to the finger.
    private static final float ATTACH_DEGREES=1.5f;
    private static final float RELEASE_DEGREES=0.8f;
    /** Start mirroring here, before the layer is shown, so the first visible frame exists. */
    private static final float PREROLL_DEGREES=0.3f;
    /**
     * The hinge reads 0-180 with ~1 degree of bias and quantises to whole degrees, so the
     * first 2 degrees are treated as flat. Everything is measured from there.
     */
    private static final float FLAT_OFFSET_DEGREES=2f;
    private volatile float lastForward;
    private float previousForward;
    private long flatSince;
    private volatile boolean layerWanted;
    /** Package currently in front; drives the per-app allow list. */
    private volatile String foregroundPkg;
    /**
     * Windows that do not represent an app the user chose: the status bar / shade / keyguard and
     * any input method. Letting them overwrite the tracked package made an excluded app show the
     * effect again a second later, because system UI is always allowed.
     */
    private boolean isTransient(String pkg) {
        if(pkg==null) return false;
        if("com.android.systemui".equals(pkg)) return true;
        try {
            android.view.inputmethod.InputMethodManager imm=getSystemService(android.view.inputmethod.InputMethodManager.class);
            if(imm!=null) for(android.view.inputmethod.InputMethodInfo info:imm.getEnabledInputMethodList())
                if(info.getPackageName().equals(pkg)) return true;
        } catch(RuntimeException ignored) {}
        return false;
    }
    private long lastResolveNanos;

    /**
     * The package actually in front. Accessibility events alone were not enough: the position
     * is unknown when a session starts, and system UI / IME windows overwrite it, which let an
     * excluded app show the effect again a second later. Reading the window stack fixes both.
     */
    private String resolveForeground() {
        long now=System.nanoTime();
        if(foregroundPkg!=null && now-lastResolveNanos<250_000_000L) return foregroundPkg;
        lastResolveNanos=now;
        try {
            java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows=getWindows();
            android.view.accessibility.AccessibilityWindowInfo best=null;
            if(windows!=null) for(android.view.accessibility.AccessibilityWindowInfo w:windows) {
                if(w.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if(best==null || w.getLayer()>best.getLayer()) best=w;
            }
            if(best!=null && best.getRoot()!=null) {
                CharSequence pkg=best.getRoot().getPackageName();
                String name=pkg==null?"":pkg.toString();
                if(name.length()>0 && !isTransient(name)) return name;
            }
        } catch(RuntimeException ignored) {}
        return foregroundPkg;
    }
    /** When the layer was last attached: guards against attach/release flapping. */
    private long attachedAt;
    /** First frame where the fold was seen closing, used to log the release latency. */
    private long unfoldStartedAt;
    private boolean lastGateFolded,lastGateAllowed;
    private String lastGatePkg;
    private static boolean equal(String a,String b) {return a==null?b==null:a.equals(b);}

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
        FoldView old=view;view=null;
        releaseGlassHost();
        if(old!=null) {old.releaseResources();main.postDelayed(()-> {try{old.onPause();}catch(RuntimeException ignored){}},200);}
        if(wanted) syncLayer();
    }
    private void syncLayer() {
        if(!active) return;
        // Flat with nothing attached: the answer is already known, and resolveForeground() is
        // an IPC round trip the 500 ms watchdog would otherwise pay forever.
        if(!layerWanted && !layerAttached && lastForward<=PREROLL_DEGREES) return;
        long now=SystemClock.uptimeMillis();
        // Use the angle that is actually on screen, not the raw sensor one: while unfolding the
        // raw value reaches the release threshold while the frame is still visibly bent, and
        // dropping the layer then made the last few degrees snap flat in a single frame.
        float shown=view!=null?view.smoothedDegrees():lastForward;
        boolean folded;
        if(layerWanted) {
            // Hold the layer through brief flat readings: the hinge reports whole degrees and
            // dips to 0 while the fold is still in progress.
            if(lastForward>RELEASE_DEGREES) {flatSince=0;folded=true;}
            else {
                if(flatSince==0) flatSince=now;
                folded=now-flatSince<350 || now-attachedAt<600;
            }
        } else {
            flatSince=0;
            folded=shown>ATTACH_DEGREES && lastForward>ATTACH_DEGREES;
        }
        String front=resolveForeground();
        if(front!=null) foregroundPkg=front;
        boolean allowed=AppFilter.allows(foregroundPkg);
        if(folded!=lastGateFolded || allowed!=lastGateAllowed || !equal(front,lastGatePkg)) {
            lastGateFolded=folded;lastGateAllowed=allowed;lastGatePkg=front;
            android.util.Log.i("DuoFold","gate pkg="+front+" allowed="+allowed+" folded="+folded);
        }
        // Pre-roll: the mirror takes a moment to deliver its first frame. Starting it a couple
        // of degrees early means the layer is already fed when it becomes visible.
        boolean closing=lastForward>previousForward;
        previousForward=lastForward;
        if(!layerAttached && closing && captureSurface!=null && captureSurface.isValid() && !captureReady
                && lastForward>PREROLL_DEGREES && view!=null) {
            beginCapture(captureSurface,captureWidth,captureHeight);
        }
        if(layerAttached && displaySizeChanged()) {rebuildLayer();return;}
        boolean want=folded && innerDisplayActive() && allowed;
        if(want!=layerWanted) android.util.Log.i("DuoFold","sync want="+want
                +" forward="+lastForward+" inner="+innerDisplayActive()+" host="+(glassHost!=null));
        if(want) {
            if(!layerWanted) attachedAt=now;
            layerWanted=true;
            if(glassHost==null) prepareLayer();
            if(!layerAttached) attachLayer();
            return;
        }
        layerWanted=false;
        if(glassHost==null && view==null) return;
        {
            stopCapture();
            FoldView old=view;view=null;
            releaseGlassHost();          // input first
            if(old!=null) {
                old.releaseResources();
                // GLSurfaceView.onPause() waits for the GL thread; keep it off this path so the
                // layer is gone before the pause round trip starts.
                main.postDelayed(()-> {try{old.onPause();}catch(RuntimeException ignored){}},200);
            }
            status=t("已展开，触屏恢复正常；折起来才会出现效果","Flat: touch restored; fold to see the effect");
            main.postDelayed(this::prewarmLayer,400);
        }
    }
    /**
     * The capture can die while the layer stays up (another app coming to the front, or the
     * panel changing). Rebuild the layer when its surface is gone, otherwise just restart the
     * stream. The two copies of this logic that used to exist now live here.
     */
    private void recoverCapture() {
        if(!active || !layerWanted) return;
        boolean rebuild=view==null || glassHost==null || captureSurface==null || !captureSurface.isValid();
        android.util.Log.i("DuoFold","capture stalled, rebuild="+rebuild+" #"+captureRetries);
        lastFrame=SystemClock.uptimeMillis();started=SystemClock.uptimeMillis();
        if(rebuild) {layerWanted=false;syncLayer();}
        else beginCapture(captureSurface,captureWidth,captureHeight);
    }
    private void restartIfStalled() {
        if(!active || !layerWanted) return;
        if(SystemClock.uptimeMillis()-lastFrame<700) return;
        recoverCapture();
    }
    private void stopCapture() {
        captureReady=false;captureStarting=false;
        try {IFoldBridge bridge=client.service();if(bridge!=null)bridge.stop();}catch(Exception ignored){}
    }
    /** True when the panel is no longer the size the current layer was built for. */
    private boolean displaySizeChanged() {
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        return metrics.widthPixels!=preparedWidth || metrics.heightPixels!=preparedHeight;
    }
    private int preparedWidth,preparedHeight;
    private boolean layerAttached;

    /**
     * Builds the host and the GL view, compiles the shaders and gets a surface, but does not show
     * anything. Doing this ahead of the first fold is what removes the hitch at the start: the
     * fold itself then only has to attach an already warm layer.
     */
    private void prepareLayer() {
        if(glassHost!=null) return;
        int overlayGeneration=generation;
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        final int w=metrics.widthPixels,h=metrics.heightPixels;
        try {
            Display display=wm.getDefaultDisplay();
            long startedPrepare=SystemClock.uptimeMillis();
            glassHost=new SurfaceControlViewHost(this,display,new android.os.Binder());
            view=new FoldView(this,new FoldView.Listener() {
                @Override public void onReady(Surface surface,int cw,int ch) {
                    captureSurface=surface;captureWidth=cw;captureHeight=ch;
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
                        .setLayer(control,1000000).setAlpha(control,0f).setVisibility(control,false);
                transaction.apply();
            }
            try {
                IFoldBridge bridge=client.service();
                if(bridge!=null) bridge.makeGlassPassThrough(control);
            } catch(Exception error) {android.util.Log.w("DuoFold","pass-through",error);}
            glassLayer=control;layerAttached=false;
            preparedWidth=w;preparedHeight=h;
            android.util.Log.i("DuoFold","layer prepared in "+(SystemClock.uptimeMillis()-startedPrepare)+"ms "+w+"x"+h);
        } catch(Throwable error) {
            android.util.Log.e("DuoFold","prepare layer",error);
            releaseGlassHost();
        }
    }

    /** Shows the prepared layer and starts the mirror. */
    private void attachLayer() {
        // Half-open posture can switch the inner panel to a different rotation, and a layer
        // built for the old size is cropped to nothing (it stays invisible). Rebuild to match.
        if(glassLayer!=null && displaySizeChanged()) {
            android.util.Log.i("DuoFold","display size changed, rebuilding layer");
            releaseGlassHost();
            prepareLayer();
        }
        SurfaceControl control=glassLayer;
        if(control==null) return;
        try {
            long startedAttach=SystemClock.uptimeMillis();
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                transaction.setAlpha(control,1f).setVisibility(control,true).apply();
            }
            // Hosted layers composite on this ROM only through the accessibility display hook.
            java.lang.reflect.Method attach=AccessibilityService.class
                    .getMethod("attachAccessibilityOverlayToDisplay",int.class,SurfaceControl.class);
            attach.invoke(this,wm.getDefaultDisplay().getDisplayId(),control);
            layerAttached=true;
            android.util.Log.i("DuoFold","layer attached in "+(SystemClock.uptimeMillis()-startedAttach)+"ms");
            // No capture start here: the pre-roll already began it while the layer was invisible.
            // Starting twice ran two capture loops against one surface and the second call
            // returned a null buffer, which killed the session.
            if(captureSurface!=null && captureSurface.isValid() && !captureReady
                    && lastForward>PREROLL_DEGREES) beginCapture(captureSurface,captureWidth,captureHeight);
        } catch(Throwable error) {
            android.util.Log.e("DuoFold","attach layer",error);
            sessionFailed(t("无法创建显示图层","Could not create the display layer"));
        }
    }

    /** Builds the next layer while the panel is flat so the following fold starts warm. */
    private void prewarmLayer() {
        if(!active || layerWanted || glassHost!=null) return;
        prepareLayer();
    }

    /**
     * Drops the layer in the order that gives input back soonest: hide, release the host (that is
     * what unregisters the input window), and only then let the GL thread finish tearing down.
     */
    private void releaseGlassHost() {
        SurfaceControl layer=glassLayer;
        SurfaceControlViewHost host=glassHost;
        glassHost=null;glassLayer=null;layerAttached=false;
        long started=SystemClock.uptimeMillis();
        if(layer!=null) {
            try(SurfaceControl.Transaction transaction=new SurfaceControl.Transaction()) {
                transaction.setVisibility(layer,false).apply();
            } catch(Throwable error) {android.util.Log.w("DuoFold","hide layer",error);}
        }
        if(host!=null) {
            try{host.release();}catch(Throwable error){android.util.Log.w("DuoFold","release host",error);}
        }
        android.util.Log.i("DuoFold","layer released in "+(SystemClock.uptimeMillis()-started)+"ms"+(unfoldStartedAt>0?" ("+(SystemClock.uptimeMillis()-unfoldStartedAt)+"ms after unfold began)":""));
        unfoldStartedAt=0;
    }
    private void beginCapture(Surface surface,int w,int h) {
        if(!active || view==null || captureStarting) return;
        captureStarting=true;
        clearMotionSources();captureReady=false;started=SystemClock.uptimeMillis();lastFrame=0;
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
                            if(sequence%120==0) android.util.Log.i("DuoFold","frames="+sequence);
                            if(!captureReady) {
                                captureReady=true;
                                if(!screenUsable()) {suspendSession(t("等待解锁","Waiting for unlock"));return;}
                                try {clearMotionSources();recovery.recovered();status=runningStatus();}
                                catch(RuntimeException e) {sessionFailed(e.toString());}
                            }
                        });
                    }
                    @Override public void onError(String message) {main.post(()->{
                        if(session!=generation) return;
                        if(message!=null && message.contains("denied screen capture")) captureRefused(message);
                        else sessionFailed(message);
                    });}
                });
            } catch(Exception e) {main.post(()->{if(session==generation) sessionFailed(e.toString());});}
            finally {main.post(()->captureStarting=false);}
        });
    }
    private final Runnable watchdog=new Runnable() {
        @Override public void run() {
            if(!active) return;
            if(!screenUsable()) {suspendSession(t("屏幕已锁定，解锁后自动恢复","Screen locked; resumes after unlock"));return;}
            long now=SystemClock.uptimeMillis();
            boolean liveCapture=Build.VERSION.SDK_INT>=31;
            // A capture that was just (re)started has lastFrame 0 and frames 0 for a moment;
            // treating that as a stall made the watchdog restart it in a loop.
            if(layerWanted && ((liveCapture && lastFrame>0 && now-lastFrame>1500)
                    || (lastFrame==0 && now-started>5000 && now-attachedAt>4000))) {
                // Switching to another app (or the panel changing) can drop the capture while
                // the layer is still up. Restart it instead of tearing the session down.
                if(captureRetries<40) {captureRetries++;recoverCapture();}
                else {
                    sessionFailed(t("画面更新超时，已恢复原始屏幕","Frame update timed out; original screen restored"));return;
                }
            }
            syncLayer();
            if(!layerWanted && captureReady) stopCapture();
            IFoldBridge bridge=client.service();
            if(bridge==null) {bridgeChanged.run();return;}
            int session=generation;
            io.execute(()->{try{if(session==generation)bridge.heartbeat();}catch(Exception e){main.post(()-> {
                if(active && session==generation) sessionFailed(t("心跳中断","Heartbeat lost"));
            });}});
            main.postDelayed(this,500);
        }
    };
    /**
     * The overlay is purely visual. Intercepting motion events means the whole screen stops
     * responding unless every event is re-injected, so the source list is always cleared. That
     * also means no motion event ever reaches this service, which is why the old three-finger
     * exit never worked.
     */
    private void clearMotionSources() {
        if(Build.VERSION.SDK_INT<34) return;
        AccessibilityServiceInfo info=getServiceInfo();
        if(info==null) return;
        info.setMotionEventSources(0);
        setServiceInfo(info);
    }
    public void stopSession(String reason) {
        if(recovery!=null) {recovery.stop();saveRecovery();}
        // Only an explicit stop ends the guard; an accessibility drop must leave it running.
        GuardService.stop(this);
        releaseSession(reason);
    }
    private void releaseSession(String reason) {
        main.removeCallbacks(resumeSession);
        // Release original input before doing any binder work or GPU cleanup.
        try {clearMotionSources();} catch(RuntimeException e) {android.util.Log.w("DuoFold","Cannot reset input on disconnected service",e);}
        active=false;captureReady=false;generation++;status=reason;
        android.util.Log.i("DuoFold", "Stopped: "+reason+"; frames="+frames);
        main.removeCallbacks(watchdog);
        if(sensor!=null) {sensor.stop();sensor=null;}
        FoldView old=view;view=null;
        // Unconditional: prepareLayer() can fail after the host exists but before the view does,
        // and a host left behind stacks a second layer on the next attach.
        releaseGlassHost();
        if(old!=null) {old.releaseResources();main.postDelayed(()-> {try{old.onPause();}catch(RuntimeException ignored){}},200);}
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
        // Every fold fires a configuration change. Rebuilding on all of them tore the layer down
        // and rebuilt it mid-gesture, which is what made the effect come and go. Only a real
        // size/rotation change needs a fresh layer.
        if(layerAttached && displaySizeChanged()) rebuildLayer();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(active && !screenUsable()) suspendSession(t("屏幕已锁定，解锁后自动恢复","Screen locked; resumes after unlock"));
        // Switching to another app tears the Shizuku capture down while the layer stays up,
        // so the effect silently disappeared in every third-party app. Re-arm it here.
        if(event.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg=event.getPackageName();
            String name=pkg==null?null:pkg.toString();
            if(isTransient(name)) name=null;
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
        io.shutdown();super.onDestroy();
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
        return t("运行中 · 用通知栏的停止按钮退出","Running · stop from the notification");
    }
}
