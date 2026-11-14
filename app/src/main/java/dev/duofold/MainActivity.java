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
    private static final int FG=0xfff0f2f7,MUTED=0xff9299a6,ACCENT=0xffbfd2ff,BG=0xff101216;
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
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(24),dp(18),dp(24),dp(28));
        scroll.addView(body);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> {
            if(Build.VERSION.SDK_INT>=30) {
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout setup=card(body);
        setup.addView(text(t("开启步骤","Setup"),22,FG));
        bridgeStatus=text(client.message(),13,MUTED);setup.addView(bridgeStatus);
        button(setup,t("01   连接并授权 Shizuku","01   Connect & authorize Shizuku"),()->client.request(),false);
        accessStatus=text(t("等待辅助服务","Waiting for accessibility service"),13,MUTED);add(setup,accessStatus,12,0);
        button(setup,t("02   开启屏幕效果辅助服务","02   Enable the screen effect service"),this::openAccessibility,false);
        // MIUI freezes background apps after a few seconds and drops the effect service with
        // them, so ask for the battery-optimisation exemption up front.
        button(setup,t("02b  允许后台运行（关闭电池优化）","02b  Allow background running"),()-> {
            try {
                Intent intent=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:"+getPackageName()));
                startActivity(intent);
            } catch(Exception error) {
                Toast.makeText(this,t("请在系统设置里把本应用的省电策略设为无限制","Set battery saver to unrestricted for this app"),Toast.LENGTH_LONG).show();
            }
        },false);
        add(setup,text(t("辅助服务用于显示效果与修正触控。画面只在本机内存处理，不保存、不上传。",
                "The accessibility service shows the effect and remaps touches. Frames stay in local memory: never saved, never uploaded."),12,MUTED),10,0);
        liveStatus=text(t("点下面的按钮开启效果","Tap the button below to start"),14,FG);add(setup,liveStatus,16,0);
        progressText=text("",12,MUTED);add(setup,progressText,6,0);
        start=button(setup,t("03   开启全局效果","03   Start full-screen effect"),()-> {
            FoldService s=FoldService.current();
            if(s!=null && (s.isActive() || s.isResumePending())) s.stopSession(t("已手动停止","Stopped manually"));else requestStart();
        },false);
        add(setup,text(Build.VERSION.SDK_INT>=34
                ?t("三指同时触屏或使用通知栏按钮，随时停止效果。","Stop anytime with a three-finger touch or the notification button.")
                :t("使用通知栏按钮，随时停止效果。","Stop anytime with the notification button."),12,ACCENT),12,0);

        LinearLayout perApp=card(body);
        perApp.addView(text(t("按应用开启",  "Per-app control"),19,FG));
        add(perApp,text(t("默认所有应用都生效，可以挑出不想生效的应用，或者反过来只让选中的应用生效。锁屏与本应用始终生效。",
                "By default every app gets the effect; you can exclude some, or switch to allowing only the ones you pick. The lock screen and this app are always included."),12,MUTED),10,0);
        appFilterStatus=text(AppFilter.summary(),13,ACCENT);
        add(perApp,appFilterStatus,10,0);
        button(perApp,t("选择应用","Choose apps"),()->startActivity(new Intent(this,AppPickerActivity.class)),true);

        LinearLayout tuning=card(body);
        tuning.addView(text(t("调到舒服的手感","Tune the feel"),19,FG));
        add(tuning,text(t("最大倾斜角度（折到这个角度时效果最强）","Max tilt angle (full effect at this fold)"),13,MUTED),18,4);
        RadioGroup rangeGroup=new RadioGroup(this);
        rangeGroup.setOrientation(LinearLayout.HORIZONTAL);
        int storedRange=Math.round(prefs.getFloat("range",150f));
        boolean matched=false;
        for(int degrees:new int[]{30,60,90,120,150}) {
            RadioButton option=new RadioButton(this);
            option.setId(View.generateViewId());option.setTag(degrees);
            option.setText(degrees+"°");option.setTextSize(13);option.setTextColor(FG);
            option.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            option.setGravity(Gravity.CENTER);
            rangeGroup.addView(option,new RadioGroup.LayoutParams(0,dp(48),1f));
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
        add(tuning,text(t("采集分辨率（越高越清晰，越低越跟手；改动在下次折起时生效）","Capture resolution (higher = sharper, lower = snappier; applies on the next fold)"),12,ACCENT),16,0);
        RadioGroup resolutionGroup=new RadioGroup(this);
        resolutionGroup.setOrientation(LinearLayout.HORIZONTAL);
        int storedWidth=prefs.getInt("captureWidth",0);
        for(int[] option:new int[][]{{720,720},{960,960},{0,1224}}) {
            RadioButton choice=new RadioButton(this);
            choice.setId(View.generateViewId());choice.setTag(option[0]);
            choice.setText(option[0]==0?t("原生","Native"):String.valueOf(option[1]));
            choice.setTextSize(13);choice.setTextColor(FG);choice.setGravity(Gravity.CENTER);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            resolutionGroup.addView(choice,new RadioGroup.LayoutParams(0,dp(48),1f));
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
        add(tuning,text(t("折叠时上半屏高度：+100% 越折越矮（原效果）· 0% 不变 · -100% 越折越高","Leaf height: +100% shrinks, 0% constant, -100% grows"),12,ACCENT),14,0);
        slider(tuning,t("高度变化","Height response"),-100,100,prefs.getFloat("heightBias",1f)*100,value->{prefs.edit().putFloat("heightBias",value/100f).apply();EffectPrefs.set(this,value/100f);},"%");
        add(tuning,text(t("左右两条边的倾斜：+60% 上边张开（上宽下窄）· 0% 两条边竖直 · -60% 上边收拢（上窄下宽）","Side edge slant: +60% wider on top, 0% vertical, -60% narrower on top"),12,ACCENT),14,0);
        slider(tuning,t("左右边倾斜","Side slant"),-60,60,prefs.getFloat("sideSlant",0f)*100,value->{prefs.edit().putFloat("sideSlant",value/100f).apply();EffectPrefs.setSlant(this,value/100f);},"%");
        add(tuning,text(t("圆角：半径（屏幕短边占比）与圆度（数值越小越圆）","Corner: radius (% of short side) and roundness (smaller = rounder)"),12,ACCENT),14,0);
        slider(tuning,t("圆角半径","Corner radius"),2,25,prefs.getFloat("cornerRadius",0.095f)*100,
                value->{prefs.edit().putFloat("cornerRadius",value/100f).apply();EffectPrefs.setCornerRadius(this,value/100f);},"%");
        slider(tuning,t("圆角圆度","Corner roundness"),20,60,prefs.getFloat("cornerPower",3.2f)*10,
                value->{float power=value/10f;prefs.edit().putFloat("cornerPower",power).apply();EffectPrefs.setCornerPower(this,power);},"");

        LinearLayout effects=card(body);
        effects.addView(text(t("动画效果","Animation effects"),19,FG));
        add(effects,text(t("选择后立即应用，自动记住你的选择。","Applies instantly and remembers your choice."),12,MUTED),6,10);
        RadioGroup choices=new RadioGroup(this);
        EffectStyle selected=EffectStyle.fromId(prefs.getString("effectStyle","classic"));
        for(EffectStyle style:EffectStyle.values()) {
            RadioButton choice=new RadioButton(this);
            choice.setId(View.generateViewId());choice.setTag(style);
            choice.setText(style.label());choice.setTextSize(14);choice.setTextColor(FG);
            choice.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            choice.setMinHeight(dp(48));
            choices.addView(choice,new RadioGroup.LayoutParams(-1,-2));
            if(style==selected)choices.check(choice.getId());
        }
        effects.addView(choices);
        TextView effectDescription=text(selected.summary(),12,MUTED);
        add(effects,effectDescription,8,0);
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
        bridgeStatus.setText(client.message());bridgeStatus.setTextColor(client.service()!=null?ACCENT:MUTED);
        FoldService s=FoldService.current();boolean active=s!=null&&s.isActive();
        accessStatus.setText(s!=null?t("屏幕效果辅助服务已连接","Screen effect service connected")
                :t("等待开启屏幕效果辅助服务","Waiting for the screen effect service"));
        accessStatus.setTextColor(s!=null?ACCENT:MUTED);
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
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(20),dp(20),dp(20),dp(20));
        GradientDrawable shape=new GradientDrawable();shape.setColor(0xff1b1e24);shape.setCornerRadius(dp(24));shape.setStroke(dp(1),0xff292e38);card.setBackground(shape);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(14);parent.addView(card,lp);return card;
    }
    private TextView text(String text,int size,int color) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t;
    }
    private void add(LinearLayout p,View v,int top,int bottom) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(top);lp.bottomMargin=dp(bottom);p.addView(v,lp);
    }
    private Button button(LinearLayout p,String title,Runnable action,boolean filled) {
        Button b=new Button(this);b.setText(title);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(filled?BG:ACCENT);
        GradientDrawable bg=new GradientDrawable();bg.setColor(filled?ACCENT:0xff252b36);bg.setCornerRadius(dp(16));b.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(50));lp.topMargin=dp(10);p.addView(b,lp);b.setOnClickListener(v->action.run());return b;
    }
    private interface ValueListener {void changed(float value);}
    private void slider(LinearLayout parent,String label,int min,int max,float initial,ValueListener listener,String unit) {
        TextView value=text(label+"  "+Math.round(initial)+unit,13,MUTED);add(parent,value,18,4);
        SeekBar seek=new SeekBar(this);seek.setMax(max-min);seek.setProgress(Math.round(initial)-min);parent.addView(seek,new LinearLayout.LayoutParams(-1,dp(36)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar bar,int p,boolean user){value.setText(label+"  "+(min+p)+unit);if(user)listener.changed(min+p);}
            @Override public void onStartTrackingTouch(SeekBar b){} @Override public void onStopTrackingTouch(SeekBar b){}
        });
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
