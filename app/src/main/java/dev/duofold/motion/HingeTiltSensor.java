package dev.duofold.motion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Replaces the gyroscope pose source on a foldable: the tilt comes straight from the hinge.
 * The fold axis is the hinge line, so only the forward axis is used; the moving leaf is the
 * upper half, which the renderer anchors at the screen centre.
 */
public final class HingeTiltSensor implements SensorEventListener {
    public interface Listener {void onTilt(float progress,float forwardDegrees,float sideDegrees);}
    /** Alias so both pose sources can share one callback type. */
    private final android.content.Context context;
    private final SensorManager manager;
    private final Listener listener;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean polling;
    private Sensor hinge;
    private float lastReported=Float.NaN;

    public HingeTiltSensor(Context context,Listener listener){
        this.context=context.getApplicationContext();
        this.manager=context.getSystemService(SensorManager.class);
        this.listener=listener;
    }
    public boolean start(){
        hinge=manager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if(hinge==null)return false;
        boolean ok=manager.registerListener(this,hinge,16667);
        if(ok) startDebugPolling();
        return ok;
    }
    /** Debug builds only: "settings put global duofold_debug_angle 40" drives the angle over adb. */
    private void startDebugPolling(){
        if(polling)return;
        polling=true;
        handler.postDelayed(new Runnable(){
            @Override public void run(){
                if(!polling)return;
                // Expires by itself, so a forgotten value can never leave the layer attached
                // and the screen unresponsive.
                long expire=Settings.Global.getLong(context.getContentResolver(),"duofold_debug_expire",0L);
                float angle=Settings.Global.getFloat(context.getContentResolver(),"duofold_debug_angle",-1f);
                if(angle>=0f && System.currentTimeMillis()<expire)
                    emit(180f-Math.max(0f,Math.min(180f,angle)));
                handler.postDelayed(this,250);
            }
        },250);
    }
    public void stop(){
        polling=false;handler.removeCallbacksAndMessages(null);
        manager.unregisterListener(this);
    }

    @Override public void onSensorChanged(SensorEvent event){
        if(event.values.length==0)return;
        float angle=event.values[0];
        if(!Float.isFinite(angle))return;
        // Folded = 0 degrees of hinge travel, flat = 180; the renderer wants the deviation.
        emit(180f-Math.max(0f,Math.min(180f,angle)));
    }
    private void emit(float forward){
        if(Math.abs(forward-lastReported)<0.2f)return;
        lastReported=forward;
        listener.onTilt(1f-forward/180f,forward,0f);
    }
    @Override public void onAccuracyChanged(Sensor sensor,int accuracy){}
}
