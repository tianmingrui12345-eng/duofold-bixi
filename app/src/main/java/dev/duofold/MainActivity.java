package dev.duofold;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Switch;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import dev.duofold.bridge.BridgeClient;
import dev.duofold.motion.EffectStyle;

import static dev.duofold.UiText.t;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler(android.os.Looper.getMainLooper());
    private BridgeClient client;
    private SharedPreferences prefs;
    private TextView bridgeStatus,accessStatus,liveStatus,progressText;
    private Button start;
    private TextView appFilterStatus;
    private boolean pendingStart;
    private final Runnable update=new Runnable() {
        @Override public void run() { refresh();handler.postDelayed(this,400); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("duo",0);client=BridgeClient.get(this);
        int motionVersion=prefs.getInt("motionVersion",0);
        if(motionVersion<3) {
            SharedPreferences.Editor migration=prefs.edit().putInt("motionVersion",3);
            if(motionVersion<2)migration.putFloat("range",180).putFloat("intensity",1);
            migration.apply();
        }
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(Ui.dp(this,24),Ui.dp(this,18),Ui.dp(this,24),Ui.dp(this,28));
        scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> {
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout setup=Ui.card(this,body);
        setup.addView(Ui.text(this,t("开启步骤","Setup"),22,Ui.FG));
        bridgeStatus=Ui.text(this,client.message(),13,Ui.MUTED);setup.addView(bridgeStatus);
        Ui.button(this,setup,t("01   连接并授权 Shizuku","01   Connect & authorize Shizuku"),()->client.request(),false);
        accessStatus=Ui.text(this,t("等待辅助服务","Waiting for accessibility service"),13,Ui.MUTED);Ui.add(this,setup,accessStatus,12,0);
        Ui.button(this,setup,t("02   开启屏幕效果辅助服务","02   Enable the screen effect service"),this::openAccessibility,false);
        // MIUI freezes background apps after a few seconds and drops the effect service with
        // them, so ask for the battery-optimisation exemption up front.
        Ui.button(this,setup,t("02b  允许后台运行（关闭电池优化）","02b  Allow background running"),()-> {
            try {
                Intent intent=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            } catch(Exception error) {
                Toast.makeText(this,t("请在系统设置里把本应用的省电策略设为无限制","Set battery saver to unrestricted for this app"),Toast.LENGTH_LONG).show();
            }
        },false);
        Ui.add(this,setup,Ui.text(this,t("辅助服务用于显示效果与修正触控。画面只在本机内存处理，不保存、不上传。",
                "The accessibility service shows the effect and remaps touches. Frames stay in local memory: never saved, never uploaded."),12,Ui.MUTED),10,0);
        liveStatus=Ui.text(this,t("点下面的按钮开启效果","Tap the button below to start"),14,Ui.FG);Ui.add(this,setup,liveStatus,16,0);
        progressText=Ui.text(this,"",12,Ui.MUTED);Ui.add(this,setup,progressText,6,0);
        start=Ui.button(this,setup,t("03   开启全局效果","03   Start full-screen effect"),()-> {
            FoldService s=FoldService.current();
            if(s!=null && (s.isActive() || s.isResumePending())) s.stopSession(t("已手动停止","Stopped manually"));else requestStart();
        },false);
        Ui.add(this,setup,Ui.text(this,t("随时可以从通知栏点「停止」恢复原始屏幕。",
                "Stop anytime from the notification."),12,Ui.ACCENT),12,0);

        LinearLayout perApp=Ui.card(this,body);
        perApp.addView(Ui.text(this,t("按应用开启",  "Per-app control"),19,Ui.FG));
        Ui.add(this,perApp,Ui.text(this,t("默认所有应用都生效，可以挑出不想生效的应用，或者反过来只让选中的应用生效。锁屏与本应用始终生效。",
                "By default every app gets the effect; you can exclude some, or switch to allowing only the ones you pick. The lock screen and this app are always included."),12,Ui.MUTED),10,0);
        appFilterStatus=Ui.text(this,AppFilter.summary(),13,Ui.ACCENT);
        Ui.add(this,perApp,appFilterStatus,10,0);
        Ui.button(this,perApp,t("选择应用","Choose apps"),()->startActivity(new Intent(this,AppPickerActivity.class)),true);

        LinearLayout tuning=Ui.card(this,body);
        tuning.addView(Ui.text(this,t("调到舒服的手感","Tune the feel"),19,Ui.FG));
        Ui.add(this,tuning,Ui.text(this,t("最大倾斜角度（折到这个角度时效果最强）","Max tilt angle (full effect at this fold)"),13,Ui.MUTED),18,4);
        RadioGroup rangeGroup=new RadioGroup(this);
        rangeGroup.setOrientation(LinearLayout.HORIZONTAL);
        int storedRange=Math.round(prefs.getFloat("range",150f));
        boolean matched=false;
        for(int degrees:new int[]{30,60,90,120,150}) {
            RadioButton option=new RadioButton(this);
            option.setId(View.generateViewId());option.setTag(degrees);
            option.setText(degrees+"°");option.setTextSize(13);option.setTextColor(Ui.FG);
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            option.setGravity(Gravity.CENTER);
            rangeGroup.addView(option,new RadioGroup.LayoutParams(0,Ui.dp(this,48),1f));
            if(storedRange==degrees){rangeGroup.check(option.getId());matched=true;}
        }
        if(!matched) {
            for(int i=0;i<rangeGroup.getChildCount();i++) {
                RadioButton option=(RadioButton)rangeGroup.getChildAt(i);
                if(Integer.valueOf(150).equals(option.getTag())) rangeGroup.check(option.getId());
            }
            prefs.edit().putFloat("range",150f).apply();EffectPrefs.setRange(this,150f);
        }
        rangeGroup.setOnCheckedChangeListener((group,id)-> {
            View chosen=group.findViewById(id);
            if(chosen==null||!(chosen.getTag() instanceof Integer))return;
            float degrees=(Integer)chosen.getTag();
            prefs.edit().putFloat("range",degrees).apply();
            EffectPrefs.setRange(this,degrees);
        });
        tuning.addView(rangeGroup,new LinearLayout.LayoutParams(-1,-2));
        slider(tuning,t("空间强度（模糊与暗部强度）","Spatial strength (blur & shading)"),20,100,prefs.getFloat("intensity",1)*100,
                value->{prefs.edit().putFloat("intensity",value/100).apply();EffectPrefs.setIntensity(this,value/100f);},"%");
        Ui.add(this,tuning,Ui.text(this,t("采集分辨率（越高越清晰，越低越跟手；改动在下次折起时生效）","Capture resolution (higher = sharper, lower = snappier; applies on the next fold)"),12,Ui.ACCENT),16,0);
        RadioGroup resolutionGroup=new RadioGroup(this);
        resolutionGroup.setOrientation(LinearLayout.HORIZONTAL);
        int storedWidth=prefs.getInt("captureWidth",0);
        for(int[] option:new int[][]{{720,720},{960,960},{0,1224}}) {
            RadioButton choice=new RadioButton(this);
            choice.setId(View.generateViewId());choice.setTag(option[0]);
            choice.setText(option[0]==0?t("原生","Native"):String.valueOf(option[1]));
            choice.setTextSize(13);choice.setTextColor(Ui.FG);choice.setGravity(Gravity.CENTER);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            resolutionGroup.addView(choice,new RadioGroup.LayoutParams(0,Ui.dp(this,48),1f));
            if(storedWidth==option[0])resolutionGroup.check(choice.getId());
        }
        resolutionGroup.setOnCheckedChangeListener((group,id)-> {
            View chosen=group.findViewById(id);
            if(chosen==null||!(chosen.getTag() instanceof Integer))return;
            int value=(Integer)chosen.getTag();
            prefs.edit().putInt("captureWidth",value).apply();
            EffectPrefs.setCaptureWidth(this,value);
        });
        tuning.addView(resolutionGroup,new LinearLayout.LayoutParams(-1,-2));
        // Height of the folded leaf: +100% foreshortens as you fold (reference behaviour),
        // 0% keeps the height, -100% makes it grow as the fold deepens.
        Ui.add(this,tuning,Ui.text(this,t("折叠时上半屏高度：+100% 越折越矮（原效果）· 0% 不变 · -100% 越折越高","Leaf height: +100% shrinks, 0% constant, -100% grows"),12,Ui.ACCENT),14,0);
        slider(tuning,t("高度变化","Height response"),-100,100,prefs.getFloat("heightBias",1f)*100,value->{prefs.edit().putFloat("heightBias",value/100f).apply();EffectPrefs.set(this,value/100f);},"%");
        Ui.add(this,tuning,Ui.text(this,t("高度变化曲线：线性起手就动 · 余弦慢起手（已补偿，不会先下沉）","Height curve: linear responds at once; cosine starts slowly without the initial dip"),12,Ui.MUTED),10,4);
        RadioGroup heightCurve=new RadioGroup(this);
        heightCurve.setOrientation(LinearLayout.HORIZONTAL);
        float storedCurve=prefs.getFloat("heightCurve",0f);
        String[] curveLabels={t("线性","Linear"),t("余弦（平滑起手）","Cosine (smoothed)")};
        for(int i=0;i<2;i++) {
            RadioButton option=new RadioButton(this);
            option.setId(View.generateViewId());option.setTag(i);
            option.setText(curveLabels[i]);option.setTextSize(13);option.setTextColor(Ui.FG);
            option.setGravity(Gravity.CENTER);
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            heightCurve.addView(option,new RadioGroup.LayoutParams(0,Ui.dp(this,48),1f));
            if((int)storedCurve==i)heightCurve.check(option.getId());
        }
        heightCurve.setOnCheckedChangeListener((group,id)-> {
            View chosen=group.findViewById(id);
            if(chosen==null||!(chosen.getTag() instanceof Integer))return;
            int value=(Integer)chosen.getTag();
            prefs.edit().putFloat("heightCurve",value).apply();
            EffectPrefs.setHeightCurve(this,value);
        });
        tuning.addView(heightCurve,new LinearLayout.LayoutParams(-1,-2));
        Ui.add(this,tuning,Ui.text(this,t("左右两条边的倾斜：+60% 上边张开（上宽下窄）· 0% 两条边竖直 · -60% 上边收拢（上窄下宽）","Side edge slant: +60% wider on top, 0% vertical, -60% narrower on top"),12,Ui.ACCENT),14,0);
        slider(tuning,t("左右边倾斜","Side slant"),-60,60,prefs.getFloat("sideSlant",0f)*100,value->{prefs.edit().putFloat("sideSlant",value/100f).apply();EffectPrefs.setSlant(this,value/100f);},"%");
        Ui.add(this,tuning,Ui.text(this,t("圆角：半径（屏幕短边占比）与圆度（数值越小越圆）","Corner: radius (% of short side) and roundness (smaller = rounder)"),12,Ui.ACCENT),14,0);
        slider(tuning,t("圆角半径","Corner radius"),2,25,prefs.getFloat("cornerRadius",0.095f)*100,
                value->{prefs.edit().putFloat("cornerRadius",value/100f).apply();EffectPrefs.setCornerRadius(this,value/100f);},"%");
        slider(tuning,t("圆角圆度","Corner roundness"),20,60,prefs.getFloat("cornerPower",3.2f)*10,
                value->{float power=value/10f;prefs.edit().putFloat("cornerPower",power).apply();EffectPrefs.setCornerPower(this,power);},"");

        LinearLayout effects=Ui.card(this,body);
        effects.addView(Ui.text(this,t("动画效果","Animation effects"),19,Ui.FG));
        Ui.add(this,effects,Ui.text(this,t("选择后立即应用，自动记住你的选择。","Applies instantly and remembers your choice."),12,Ui.MUTED),6,10);
        RadioGroup choices=new RadioGroup(this);
        EffectStyle selected=EffectStyle.fromId(prefs.getString("effectStyle","classic"));
        for(EffectStyle style:EffectStyle.values()) {
            RadioButton choice=new RadioButton(this);
            choice.setId(View.generateViewId());choice.setTag(style);
            choice.setText(style.label());choice.setTextSize(14);choice.setTextColor(Ui.FG);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            choice.setMinHeight(Ui.dp(this,48));
            choices.addView(choice,new RadioGroup.LayoutParams(-1,-2));
            if(style==selected)choices.check(choice.getId());
        }
        effects.addView(choices);
        TextView effectDescription=Ui.text(this,selected.summary(),12,Ui.MUTED);
        Ui.add(this,effects,effectDescription,8,0);
        choices.setOnCheckedChangeListener((group,id)-> {
            RadioButton choice=group.findViewById(id);if(choice==null)return;
            EffectStyle style=(EffectStyle)choice.getTag();
            prefs.edit().putString("effectStyle",style.id).apply();
            effectDescription.setText(style.summary());
            FoldService service=FoldService.current();
            if(service!=null)service.setEffectStyle(style);
        });

        refresh();
    }
    @Override protected void onResume() {
        super.onResume();client.connect();handler.post(update);
        AppFilter.load(this);
        if(appFilterStatus!=null) appFilterStatus.setText(AppFilter.summary());
    }
    @Override protected void onPause() {handler.removeCallbacks(update);super.onPause();}
    private void refresh() {
        if(bridgeStatus==null)return;
        bridgeStatus.setText(client.message());bridgeStatus.setTextColor(client.service()!=null?Ui.ACCENT:Ui.MUTED);
        FoldService s=FoldService.current();boolean active=s!=null&&s.isActive();
        accessStatus.setText(s!=null?t("屏幕效果辅助服务已连接","Screen effect service connected")
                :t("等待开启屏幕效果辅助服务","Waiting for the screen effect service"));
        accessStatus.setTextColor(s!=null?Ui.ACCENT:Ui.MUTED);
        liveStatus.setText(s!=null?s.status():prefs.getString("lastStatus",t("点下面的按钮开启效果","Tap the button below to start")));
        progressText.setText(active?t("展开 "+Math.round(s.progress()*100)+"%  ·  已更新 "+s.frames()+" 帧",
                "Fold "+Math.round(s.progress()*100)+"%  ·  "+s.frames()+" frames updated"):"");
        start.setText((active || s!=null && s.isResumePending())?t("停止并恢复原始屏幕","Stop & restore original screen"):t("03   开启全局效果","03   Start full-screen effect"));
        start.setEnabled(active || s!=null && (s.isResumePending() || client.service()!=null));
    }
    private void requestStart() {
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            pendingStart=true;requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},51);return;
        }
        startSession();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==51 && pendingStart){pendingStart=false;startSession();}
    }
    private void startSession() {
        FoldService s=FoldService.current();
        if(s==null){openAccessibility();return;}
        s.startSession();
        if(s.isActive()) moveTaskToBack(true);
    }
    private void openAccessibility() {
        try {startActivity(new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra("android.intent.extra.COMPONENT_NAME",new ComponentName(this,FoldService.class)));}
        catch(Exception e){startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
    }
    private interface ValueListener {void changed(float value);}
    private void slider(LinearLayout parent,String label,int min,int max,float initial,ValueListener listener,String unit) {
        TextView value=Ui.text(this,label+"  "+Math.round(initial)+unit,13,Ui.MUTED);Ui.add(this,parent,value,18,4);
        SeekBar seek=new SeekBar(this);seek.setMax(max-min);seek.setProgress(Math.round(initial)-min);parent.addView(seek,new LinearLayout.LayoutParams(-1,Ui.dp(this,36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean user){value.setText(label+"  "+(min+p)+unit);if(user)listener.changed(min+p);}
            @Override public void onStartTrackingTouch(SeekBar b){} @Override public void onStopTrackingTouch(SeekBar b){}
        });
    }
}
