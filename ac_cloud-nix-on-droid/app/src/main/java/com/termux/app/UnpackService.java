package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;

import com.termux.cloud.UnpackReport;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * #1121 Unpacks this app's bundled files with no UI: the bootstrap and (termux) the rootfs
 * staged out of the companion lib, exactly what the first start of the activity does
 * ({@link TerminalDebugApi#prepare}). Started by {@link UnpackReceiver} after an update and,
 * explicitly, by Cloud Store after it installs this package.
 *
 * The manifest guards it with the fleet's signature-level TERMINAL_SESSION permission, so no
 * other app can start it. The outcome is written to files/unpack-result.json (the proof an
 * emulator test reads without opening the app) and handed to the ResultReceiver the starter
 * passed, which is how the Store shows "unpacked" or the error.
 *
 * Same file in ac_cloud-termux and ac_cloud-nix-on-droid.
 */
public class UnpackService extends Service {

    private static final String LOG_TAG = "UnpackService";
    private static final String CHANNEL = "cloud_unpack";
    private static final int NOTIFICATION_ID = 1121;
    private static final Object RUNNING = new Object();

    /** [receiver] may be null (the system broadcast has none). Throws what startForegroundService throws. */
    static void start(Context context, ResultReceiver receiver) {
        Intent intent = new Intent(context, UnpackService.class).setAction(UnpackReport.ACTION);
        if (receiver != null) intent.putExtra(UnpackReport.EXTRA_RECEIVER, receiver);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, notification());
        } catch (RuntimeException e) {
            // #1708 refused in the background at targetSdk 31+ unless the start is exempt (MY_PACKAGE_REPLACED
            // and a start by the visible Store both are): the unpack still runs, without the protection.
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not enter the foreground", e);
        }
        final ResultReceiver receiver = receiverOf(intent);
        final Context app = getApplicationContext();
        new Thread(() -> {
            String error;
            synchronized (RUNNING) {   // two starts (receiver + Store) unpack once, one after the other
                error = unpack(app);
            }
            Bundle out = new Bundle();
            out.putString("package", getPackageName());
            if (error != null) out.putString("error", error);
            if (receiver != null) {
                try { receiver.send(error == null ? UnpackReport.RESULT_OK : UnpackReport.RESULT_FAILED, out); }
                catch (RuntimeException e) { Logger.logStackTraceWithMessage(LOG_TAG, "Result not delivered", e); }
            }
            stopForeground(true);
            stopSelf(startId);
        }, "cloud-unpack").start();
        return START_NOT_STICKY;
    }

    /**
     * The reply channel, or null. A sender whose receiver class this app cannot load (BadParcelableException),
     * a wrong type or a missing extra must never stop the unpack, so every failure here means no reply.
     */
    static ResultReceiver receiverOf(Intent intent) {
        if (intent == null) return null;
        try {
            if (Build.VERSION.SDK_INT >= 33)
                return intent.getParcelableExtra(UnpackReport.EXTRA_RECEIVER, ResultReceiver.class);
            return intent.getParcelableExtra(UnpackReport.EXTRA_RECEIVER);
        } catch (RuntimeException e) {   // BadParcelableException, ClassCastException
            Logger.logStackTraceWithMessage(LOG_TAG, "Reply channel unreadable, unpacking without it", e);
            return null;
        }
    }

    /** null on success, else why not; the verdict is written to files/unpack-result.json either way. */
    private static String unpack(Context app) {
        String error = TerminalDebugApi.prepare(app);
        try {
            File file = new File(TermuxConstants.TERMUX_FILES_DIR_PATH, UnpackReport.FILE);
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(UnpackReport.json(app.getPackageName(), error, System.currentTimeMillis())
                    .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not write " + UnpackReport.FILE, e);
        }
        return error;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "Unpacking after an update", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Unpacking after the update")
            .setOngoing(true)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
