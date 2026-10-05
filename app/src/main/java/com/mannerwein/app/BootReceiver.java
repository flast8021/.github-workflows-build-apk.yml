package com.mannerwein.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the background scanner after a reboot or app update, if it was on. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) return;
        if (!Prefs.on(ctx)) return;
        try {
            ctx.startForegroundService(new Intent(ctx, ScanService.class).setAction(ScanService.ACT_START));
        } catch (Exception ignored) {}
    }
}
