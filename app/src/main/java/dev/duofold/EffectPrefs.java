package dev.duofold;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings the renderer reads every frame. heightBias controls how the folded leaf's height
 * reacts to the fold angle: 1 = shrinks like a foreshortened pane, 0 = constant, -1 = grows.
 */
public final class EffectPrefs {
    public static volatile float heightBias=1f;
    /** Slant of the leaf's side edges; see the shader comment. */
    public static volatile float sideSlant=0f;
    /** Fold angle (degrees) at which the effect reaches full strength. */
    /** Blur strength, 0.2 .. 1.0. */
    public static volatile float intensity=1f;
    /** Capture buffer width in pixels; 0 = native panel width. */
    public static volatile int captureWidth=0;
    /** Corner radius as a fraction of the panel's short side. */
    public static volatile float cornerRadius=0.095f;
    /** Physical fold angle (deg) where the effect starts accelerating towards the ceiling. */
    public static volatile float tailKnee=60f;
    /** How much of the remaining gap the tail closes: 0 = none, 1 = all of it. */
    public static volatile float tailBoost=0.6f;
    /** 0 = linear in fold progress, 1 = 1 - cos(tilt). */
    public static volatile float heightCurve=0f;
    /** Superellipse exponent: 2 = ellipse, larger = squarer. */
    public static volatile float cornerPower=3.2f;
    private EffectPrefs(){}

    public static void load(Context context){
        SharedPreferences prefs=prefs(context);
        heightBias=clamp(prefs.getFloat("heightBias",1f),-1f,1f);
        sideSlant=clamp(prefs.getFloat("sideSlant",0f),-0.6f,0.6f);
        intensity=clamp(prefs.getFloat("intensity",1f),0.2f,1f);
        captureWidth=prefs.getInt("captureWidth",0);
        heightCurve=prefs.getFloat("heightCurve",0f);
        tailKnee=clamp(prefs.getFloat("tailKnee",60f),10f,90f);
        tailBoost=clamp(prefs.getFloat("tailBoost",0.6f),0f,1f);
        cornerRadius=clamp(prefs.getFloat("cornerRadius",0.095f),0.01f,0.30f);
        cornerPower=clamp(prefs.getFloat("cornerPower",3.2f),2f,8f);
    }

    public static void set(Context context,float value){heightBias=save(context,"heightBias",value,-1f,1f);}
    public static void setSlant(Context context,float value){sideSlant=save(context,"sideSlant",value,-0.6f,0.6f);}
    public static void setIntensity(Context context,float value){intensity=save(context,"intensity",value,0.2f,1f);}
    public static void setCaptureWidth(Context context,int value){
        captureWidth=Math.max(0,value);
        prefs(context).edit().putInt("captureWidth",captureWidth).apply();
    }
    public static void setTailKnee(Context context,float value){tailKnee=save(context,"tailKnee",value,10f,90f);}
    public static void setTailBoost(Context context,float value){tailBoost=save(context,"tailBoost",value,0f,1f);}
    public static void setHeightCurve(Context context,float value){
        heightCurve=value;
        prefs(context).edit().putFloat("heightCurve",heightCurve).apply();
    }
    public static void setCornerRadius(Context context,float value){cornerRadius=save(context,"cornerRadius",value,0.01f,0.30f);}
    public static void setCornerPower(Context context,float value){cornerPower=save(context,"cornerPower",value,2f,8f);}

    public static float clamp(float value){return clamp(value,-1f,1f);}
    public static float clamp(float value,float min,float max){return Math.max(min,Math.min(max,value));}
    private static SharedPreferences prefs(Context context){
        return context.getApplicationContext().getSharedPreferences("duo",0);
    }
    /** Clamps, mirrors the value into the field's key and persists it. */
    private static float save(Context context,String key,float value,float min,float max){
        float clamped=clamp(value,min,max);
        prefs(context).edit().putFloat(key,clamped).apply();
        return clamped;
    }
}
