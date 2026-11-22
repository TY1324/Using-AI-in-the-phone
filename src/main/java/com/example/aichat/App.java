package com.example.aichat;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 全局崩溃捕获：把启动/运行时的异常显示到屏幕上，便于排查。
 * AndroidManifest 的 <application> 需要加 android:name=".App"
 */
public class App extends Application {

    static Activity currentActivity;

    @Override
    public void onCreate() {
        super.onCreate();

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                currentActivity = activity;
            }

            @Override
            public void onActivityStarted(Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityResumed(Activity activity) {
                currentActivity = activity;
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringBuilder sb = new StringBuilder();
            sb.append("发生错误：\n\n");
            sb.append(e.toString()).append("\n\n");
            StackTraceElement[] st = e.getStackTrace();
            int n = Math.min(12, st.length);
            for (int i = 0; i < n; i++) {
                sb.append(st[i].toString()).append('\n');
            }
            Throwable cause = e.getCause();
            if (cause != null) {
                sb.append("\n原因：").append(cause.toString()).append('\n');
                StackTraceElement[] cs = cause.getStackTrace();
                int cn = Math.min(10, cs.length);
                for (int i = 0; i < cn; i++) {
                    sb.append(cs[i].toString()).append('\n');
                }
            }
            showCrash(sb.toString());
        });
    }

    private static void showCrash(String msg) {
        try {
            Activity act = currentActivity;
            if (act == null) return;
            LinearLayout box = new LinearLayout(act);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(24, 24, 24, 24);
            TextView tv = new TextView(act);
            tv.setText(msg);
            tv.setTextSize(11);
            tv.setTextColor(0xFF000000);
            box.addView(tv);
            ScrollView sv = new ScrollView(act);
            sv.addView(box);
            act.setContentView(sv);
        } catch (Exception ignored) {
        }
    }
}
