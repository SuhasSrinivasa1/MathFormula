package com.mathformula.alpha;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.Instant;

/**
 * Restore the periodic, best-effort research job after reboot or package upgrade.
 * NEVER start a dataSync foreground service from BOOT_COMPLETED on Android 15.
 * A stopped/force-stopped app requires the user to launch MathFormula again.
 */
public final class ScannerBootReceiver extends BroadcastReceiver {
  @Override public void onReceive(Context context, Intent intent) {
    if(intent==null)return;
    String action=intent.getAction();
    if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&
       !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
    ResearchJob.schedule(context.getApplicationContext());
    context.getSharedPreferences("mathformula_local_monitor_v32",Context.MODE_PRIVATE)
      .edit().putLong("scheduler_restored_ms",System.currentTimeMillis())
      .putString("monitoring_state","JOBS_SCHEDULED · FOREGROUND REQUIRES OPEN APP")
      .apply();
  }
}
