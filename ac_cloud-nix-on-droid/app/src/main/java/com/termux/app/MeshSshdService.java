package com.termux.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import com.termux.BuildConfig;

/**
 * The hook another fleet app (Cloud C3 WebServer's keep-alive supervisor) uses to bring the mesh door up or down:
 * build.json::mesh_sshd.control is the declaration, test/test-mesh-sshd-control.sh holds this file to it.
 *
 * Start runs the SAME path BOOT_COMPLETED runs ({@link MeshSshdBoot#start}) with the caller's Mesh-only flag, whatever
 * mesh_sshd.start_on_boot says: that
 * flag stays the terminal's own boot default (off, #1487), this is the caller's explicit request. Stop ends the sshd
 * through its own pidfile. The sshd keeps running in this app's own uid and home, reading its own ~/.ssh/authorized_keys:
 * no key, port or auth moves.
 *
 * Exported ONLY behind the fleet's signature-level CONSTELLATION_DATA permission and with no intent filter, so the system
 * refuses every non-fleet caller before this class runs and an implicit intent can never reach it. Short-lived: a
 * foreground service for the length of the script (the caller is in the foreground, so the start is allowed), then it
 * ends itself; START_NOT_STICKY, no wake, no schedule.
 */
public class MeshSshdService extends Service {
    public static final String ACTION_START = "com.diegonmarcos.cloud.action.MESH_SSHD_START";
    public static final String ACTION_STOP = "com.diegonmarcos.cloud.action.MESH_SSHD_STOP";
    public static final String EXTRA_MESH_ONLY = "com.diegonmarcos.cloud.extra.MESH_ONLY";
    private static final String CHANNEL = "cloud_mesh_sshd";
    private static final int NOTIFICATION_ID = 1487;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        final String action = intent == null ? null : intent.getAction();
        final android.content.Context app = getApplicationContext();
        // The caller owns the Mesh-only switch and sends it with every start; absent means build.json::mesh_sshd.mesh_only.default.
        final boolean meshOnly = intent != null && intent.getBooleanExtra(EXTRA_MESH_ONLY, BuildConfig.CLOUD_MESH_ONLY_DEFAULT);
        new Thread(() -> {
            try {
                if (ACTION_START.equals(action)) MeshSshdBoot.start(app, meshOnly);
                else if (ACTION_STOP.equals(action)) MeshSshdBoot.stop(app);
            } finally {
                stopForeground(true);
                stopSelf(startId);
            }
        }, "mesh-sshd-control").start();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(
                new NotificationChannel(CHANNEL, "SSH door", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SSH door")
            .setOngoing(true)
            .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
