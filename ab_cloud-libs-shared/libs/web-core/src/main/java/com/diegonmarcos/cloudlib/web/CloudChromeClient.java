package com.diegonmarcos.cloudlib.web;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * The chrome client every fleet web view extends, never a bare WebChromeClient (#1371): full-screen video and
 * full-screen canvases (requestFullscreen) through onShowCustomView, hiding the system bars while it lasts.
 * Java for the same reason as {@link CloudWebClient}.
 */
public class CloudChromeClient extends WebChromeClient {
    /** #1518 told when a full-screen view starts or ends (the Browser asks for the max refresh rate only while a GPU canvas is full screen). */
    public interface FullscreenListener { void onFullscreen(boolean on); }

    public static volatile FullscreenListener fullscreenListener;

    private final Activity activity;
    private View custom;
    private CustomViewCallback callback;

    public CloudChromeClient() { this(null); }

    public CloudChromeClient(Activity activity) { this.activity = activity; }

    static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) return (Activity) c;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    /** #1537 every page's console into {@link PageConsole} (ring for the diagnostics sheet + logcat for the log export). */
    @Override
    public boolean onConsoleMessage(ConsoleMessage m) {
        if (m == null) return false;
        PageConsole.INSTANCE.record(m.messageLevel().name(), m.message(), m.sourceId(), m.lineNumber(), null, System.currentTimeMillis());
        return true;
    }

    @Override
    public void onShowCustomView(View view, CustomViewCallback cb) {
        Activity act = activity != null ? activity : activityOf(view.getContext());
        View decor = act == null ? null : act.getWindow().getDecorView();
        if (custom != null || !(decor instanceof FrameLayout)) {
            cb.onCustomViewHidden();
            return;
        }
        custom = view;
        callback = cb;
        ((FrameLayout) decor).addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        WindowCompat.getInsetsController(act.getWindow(), decor).hide(WindowInsetsCompat.Type.systemBars());
        FullscreenListener l = fullscreenListener;
        if (l != null) l.onFullscreen(true);
    }

    @Override
    public void onHideCustomView() {
        View view = custom;
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        Activity act = activity != null ? activity : activityOf(view.getContext());
        if (act != null) WindowCompat.getInsetsController(act.getWindow(), act.getWindow().getDecorView()).show(WindowInsetsCompat.Type.systemBars());
        custom = null;
        if (callback != null) callback.onCustomViewHidden();
        callback = null;
        FullscreenListener l = fullscreenListener;
        if (l != null) l.onFullscreen(false);
    }
}
