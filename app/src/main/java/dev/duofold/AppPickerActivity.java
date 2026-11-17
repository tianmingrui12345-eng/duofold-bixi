package dev.duofold;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Lets the user decide in which apps the fold effect is shown. Two semantics share one list:
 * "all apps except the ticked ones" and "only the ticked apps".
 */
public final class AppPickerActivity extends Activity {

    private final List<App> apps=new ArrayList<>();
    private final List<App> shown=new ArrayList<>();
    private RowAdapter adapter;
    private TextView hint;
    private EditText search;

    private static final class App {
        String label;
        String pkg;
        Drawable icon;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppFilter.load(this);

        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        int pad=dp(16);
        root.setPadding(pad,dp(20),pad,0);
        // Keep the title clear of the status bar and the list clear of the gesture bar.
        Ui.applyInsets(root,pad,dp(20),0);

        TextView title=new TextView(this);
        title.setText(UiText.t("哪些应用生效","Where the effect runs"));
        title.setTextColor(Ui.FG);
        title.setTextSize(21);
        title.setPadding(0,0,0,dp(10));
        root.addView(title);

        TextView note=new TextView(this);
        note.setText(UiText.t("锁屏和本应用始终生效，不受这里影响。",
                "The lock screen and this app are always included."));
        note.setTextColor(Ui.MUTED);
        note.setTextSize(12);
        note.setPadding(0,0,0,dp(12));
        root.addView(note);

        RadioGroup modes=new RadioGroup(this);
        RadioButton all=radio(UiText.t("所有应用生效（勾选的是例外）","All apps except the ticked ones"));
        RadioButton only=radio(UiText.t("仅勾选的应用生效","Only the ticked apps"));
        modes.addView(all);
        modes.addView(only);
        modes.check(AppFilter.mode()==AppFilter.MODE_ONLY?only.getId():all.getId());
        modes.setOnCheckedChangeListener((group,id)-> {
            AppFilter.setMode(this,id==only.getId()?AppFilter.MODE_ONLY:AppFilter.MODE_ALL);
            refreshHint();
            adapter.notifyDataSetChanged();
        });
        root.addView(modes);

        hint=new TextView(this);
        hint.setTextColor(Ui.ACCENT);
        hint.setTextSize(12);
        hint.setPadding(0,dp(4),0,dp(10));
        root.addView(hint);

        search=new EditText(this);
        search.setHint(UiText.t("搜索应用","Search apps"));
        search.setHintTextColor(Ui.MUTED);
        search.setTextColor(Ui.FG);
        search.setSingleLine(true);
        root.addView(search,new LinearLayout.LayoutParams(-1,-2));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int a,int b,int c) {}
            @Override public void onTextChanged(CharSequence s,int a,int b,int c) {filter(s.toString());}
            @Override public void afterTextChanged(Editable s) {}
        });

        ListView list=new ListView(this);
        list.setDivider(null);
        adapter=new RowAdapter();
        list.setAdapter(adapter);
        root.addView(list,new LinearLayout.LayoutParams(-1,0,1f));

        Button back=new Button(this);
        back.setText(UiText.t("完成","Done"));
        back.setOnClickListener(v->finish());
        LinearLayout.LayoutParams backParams=new LinearLayout.LayoutParams(-1,dp(52));
        backParams.setMargins(0,dp(8),0,dp(12));
        root.addView(back,backParams);

        setContentView(root);
        refreshHint();
        loadApps();
    }

    private RadioButton radio(String text) {
        RadioButton button=new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(text);
        button.setTextColor(Ui.FG);
        button.setTextSize(14);
        return button;
    }

    private int dp(int value) {return Ui.dp(this,value);}

    private void refreshHint() {
        hint.setText(AppFilter.summary());
    }

    private void loadApps() {
        new Thread(()-> {
            PackageManager pm=getPackageManager();
            Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved=pm.queryIntentActivities(intent,0);
            List<App> loaded=new ArrayList<>();
            for(ResolveInfo info:resolved) {
                String pkg=info.activityInfo.packageName;
                if(pkg.equals(getPackageName())) continue;
                App app=new App();
                app.pkg=pkg;
                app.label=String.valueOf(info.loadLabel(pm));
                app.icon=info.loadIcon(pm);
                if(!containsPackage(loaded,pkg)) loaded.add(app);
            }
            Collections.sort(loaded,Comparator.comparing(a->a.label.toLowerCase()));
            runOnUiThread(()-> {
                apps.clear();
                apps.addAll(loaded);
                filter(search.getText().toString());
            });
        }).start();
    }

    private static boolean containsPackage(List<App> list,String pkg) {
        for(App app:list) if(app.pkg.equals(pkg)) return true;
        return false;
    }

    private void filter(String query) {
        String needle=query==null?"":query.trim().toLowerCase();
        shown.clear();
        for(App app:apps)
            if(needle.isEmpty() || app.label.toLowerCase().contains(needle) || app.pkg.contains(needle))
                shown.add(app);
        if(adapter!=null) adapter.notifyDataSetChanged();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() {return shown.size();}
        @Override public Object getItem(int position) {return shown.get(position);}
        @Override public long getItemId(int position) {return position;}
        @Override public View getView(int position,View convert,ViewGroup parent) {
            LinearLayout row=(LinearLayout)(convert!=null?convert:buildRow());
            App app=shown.get(position);
            ImageView icon=(ImageView)row.getChildAt(0);
            LinearLayout texts=(LinearLayout)row.getChildAt(1);
            TextView label=(TextView)texts.getChildAt(0);
            TextView pkg=(TextView)texts.getChildAt(1);
            CheckBox box=(CheckBox)row.getChildAt(2);
            icon.setImageDrawable(app.icon);
            label.setText(app.label);
            pkg.setText(app.pkg);
            box.setOnCheckedChangeListener(null);
            box.setChecked(AppFilter.isMarked(app.pkg));
            box.setOnCheckedChangeListener((button,checked)-> {
                AppFilter.mark(AppPickerActivity.this,app.pkg,checked);
                refreshHint();
            });
            row.setOnClickListener(v->box.setChecked(!box.isChecked()));
            return row;
        }
        private View buildRow() {
            LinearLayout row=new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad=dp(10);
            row.setPadding(0,pad,0,pad);

            ImageView icon=new ImageView(AppPickerActivity.this);
            row.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));

            LinearLayout texts=new LinearLayout(AppPickerActivity.this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(12),0,0,0);
            TextView label=new TextView(AppPickerActivity.this);
            label.setTextColor(Ui.FG);
            label.setTextSize(15);
            TextView pkg=new TextView(AppPickerActivity.this);
            pkg.setTextColor(Ui.MUTED);
            pkg.setTextSize(11);
            texts.addView(label);
            texts.addView(pkg);
            row.addView(texts,new LinearLayout.LayoutParams(0,-2,1f));

            CheckBox box=new CheckBox(AppPickerActivity.this);
            box.setClickable(true);
            row.addView(box);
            return row;
        }
    }
}
