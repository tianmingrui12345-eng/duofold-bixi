package dev.duofold;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings that the renderer reads every frame. heightBias controls how the folded leaf's
 * height reacts to the fold angle: 1 = shrinks like a foreshortened pane (reference
 * behaviour), 0 = constant height, -1 = grows as you fold further.
 */
public final class EffectPrefs {
    public static volatile float heightBias=1f;
    /** Slant of the leaf's side edges; see the shader comment. */
    public static volatile float sideSlant=0f;
    /** Fold angle (degrees) at which the effect reaches full strength. */
    public static volatile float range=180f;
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
        SharedPreferences prefs=context.getApplicationContext().getSharedPreferences("duo",0);
        heightBias=clamp(prefs.getFloat("heightBias",1f));
        sideSlant=Math.max(-0.6f,Math.min(0.6f,prefs.getFloat("sideSlant",0f)));
        range=Math.max(15f,Math.min(180f,prefs.getFloat("range",180f)));
        intensity=Math.max(0.2f,Math.min(1f,prefs.getFloat("intensity",1f)));
        captureWidth=prefs.getInt("captureWidth",0);
        heightCurve=prefs.getFloat("heightCurve",0f);
        tailKnee=Math.max(10f,Math.min(90f,prefs.getFloat("tailKnee",60f)));
        tailBoost=Math.max(0f,Math.min(1f,prefs.getFloat("tailBoost",0.6f)));
        cornerRadius=Math.max(0.01f,Math.min(0.30f,prefs.getFloat("cornerRadius",0.095f)));
        cornerPower=Math.max(2f,Math.min(8f,prefs.getFloat("cornerPower",3.2f)));
    }
    public static void set(Context context,float value){
        heightBias=clamp(value);
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("heightBias",heightBias).apply();
    }
    public static void setSlant(Context context,float value){
        sideSlant=Math.max(-0.6f,Math.min(0.6f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("sideSlant",sideSlant).apply();
    }
    public static void setRange(Context context,float value){
        range=Math.max(15f,Math.min(180f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("range",range).apply();
    }
    public static void setIntensity(Context context,float value){
        intensity=Math.max(0.2f,Math.min(1f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("intensity",intensity).apply();
    }
    public static void setCaptureWidth(Context context,int value){
        captureWidth=Math.max(0,value);
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putInt("captureWidth",captureWidth).apply();
    }
    public static void setTailKnee(Context context,float value){
        tailKnee=Math.max(10f,Math.min(90f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("tailKnee",tailKnee).apply();
    }
    public static void setTailBoost(Context context,float value){
        tailBoost=Math.max(0f,Math.min(1f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("tailBoost",tailBoost).apply();
    }
    public static void setHeightCurve(Context context,float value){
        heightCurve=value;
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("heightCurve",heightCurve).apply();
    }
    public static void setCornerRadius(Context context,float value){
        cornerRadius=Math.max(0.01f,Math.min(0.30f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("cornerRadius",cornerRadius).apply();
    }
    public static void setCornerPower(Context context,float value){
        cornerPower=Math.max(2f,Math.min(8f,value));
        context.getApplicationContext().getSharedPreferences("duo",0)
                .edit().putFloat("cornerPower",cornerPower).apply();
    }
    public static float clamp(float value){return Math.max(-1f,Math.min(1f,value));}
}
