package dev.duofold;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The bits of UI both activities were building by hand, twice. One place to change a colour or
 * a corner radius.
 */
final class Ui {
    static final int BG=0xff101216,FG=0xfff0f2f7,MUTED=0xff9299a6,ACCENT=0xffbfd2ff;
    private static final int CARD_FILL=0xff1b1e24,CARD_STROKE=0xff292e38,FILLED_BUTTON=0xff252b36;

    private Ui() {}

    static int dp(Context context,int value) {
        return Math.round(value*context.getResources().getDisplayMetrics().density);
    }

    static LinearLayout card(Context context,LinearLayout parent) {
        LinearLayout card=new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad=dp(context,20);
        card.setPadding(pad,pad,pad,pad);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(CARD_FILL);
        shape.setCornerRadius(dp(context,24));
        shape.setStroke(dp(context,1),CARD_STROKE);
        card.setBackground(shape);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(context,14);
        parent.addView(card,lp);
        return card;
    }

    static TextView text(Context context,String value,int size,int color) {
        TextView view=new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(context,3),1);
        return view;
    }

    static void add(Context context,LinearLayout parent,View child,int top,int bottom) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.topMargin=dp(context,top);
        lp.bottomMargin=dp(context,bottom);
        parent.addView(child,lp);
    }

    static Button button(Context context,LinearLayout parent,String title,Runnable action,boolean filled) {
        Button button=new Button(context);
        button.setText(title);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(filled?BG:ACCENT);
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(filled?ACCENT:FILLED_BUTTON);
        shape.setCornerRadius(dp(context,16));
        button.setBackground(shape);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(context,50));
        lp.topMargin=dp(context,10);
        parent.addView(button,lp);
        button.setOnClickListener(v->action.run());
        return button;
    }

    /** Done steps read as filled accent buttons, pending ones stay dark. */
    static void setFilled(Button button,boolean filled) {
        if(button==null) return;
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(filled?ACCENT:FILLED_BUTTON);
        shape.setCornerRadius(dp(button.getContext(),16));
        button.setBackground(shape);
        button.setTextColor(filled?BG:ACCENT);
    }

    /** Keeps content clear of the status bar and the gesture bar. */
    static void applyInsets(android.view.View root,int horizontal,int top,int bottom) {
        root.setOnApplyWindowInsetsListener((v,insets)-> {
            v.setPadding(horizontal,top+insets.getSystemWindowInsetTop(),horizontal,
                    bottom+insets.getSystemWindowInsetBottom());
            return insets;
        });
    }
}
