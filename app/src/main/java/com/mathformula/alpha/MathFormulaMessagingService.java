package com.mathformula.alpha;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;

/**
 * Optional FCM wake-up receiver. Requires Firebase project configuration AND a trusted
 * independent sender. No broker credentials or unverifiable BUY advice in push payloads.
 * If the user force-stops the app or blocks notifications, Android may not deliver.
 */
public final class MathFormulaMessagingService extends FirebaseMessagingService {
  @Override public void onNewToken(String token){
    // FCM tokens are device identifiers; never export them to audit logs or public GitHub.
    // Registration to a user-authenticated backend remains a separate required deployment.
    getSharedPreferences("mf_fcm_v31",MODE_PRIVATE).edit()
      .putBoolean("token_available",token!=null&&!token.isEmpty())
      .putLong("token_changed_at",System.currentTimeMillis()).apply();
    new WatchStore(this).audit("PUSH","DEVICE","TOKEN_UNKNOWN","TOKEN_REFRESHED",
       "FCM device token refreshed; server registration pending",0);
  }
  @Override public void onDeletedMessages(){
    new WatchStore(this).audit("PUSH","SYNC","RECENT","COVERAGE_GAP",
       "FCM messages deleted while offline; refresh authoritative scanner on next open",0);
  }
  @Override public void onMessageReceived(RemoteMessage message){
    Map<String,String> payload=message.getData();
    String kind=payload.get("kind");
    String symbol=payload.get("symbol");
    if(kind==null||symbol==null||!symbol.matches("[A-Z0-9&-]{1,26}"))return;
    if(!kind.equals("WATCH")&&!kind.equals("CONFIRMING")&&
       !kind.equals("INVALIDATED")&&!kind.equals("REFRESH"))return;
    // Do not interpret an unverified payload as executable trade eligibility.
    if(!ResearchAlerts.permission(this))return;
    long now=System.currentTimeMillis();
    long eventMs=0;try{eventMs=Long.parseLong(payload.get("event_ms"));}
       catch(Exception ignored){}
    if(eventMs<=0||eventMs>now+3000||now-eventMs>15*60_000L)return;
    String eventKey=payload.get("event_id");
    if(eventKey==null||!eventKey.matches("[A-Za-z0-9_-]{8,96}"))return;
    android.content.SharedPreferences p=getSharedPreferences("mf_fcm_v31",MODE_PRIVATE);
    String slot="event_"+eventKey;
    if(p.getBoolean(slot,false))return;
    ResearchAlerts.channel(this);
    Intent launch=new Intent(this,HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    PendingIntent click=PendingIntent.getActivity(this,Math.abs(eventKey.hashCode()),
       launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    Notification note=new Notification.Builder(this,ResearchAlerts.CHANNEL)
      .setSmallIcon(android.R.drawable.stat_notify_more)
      .setContentTitle("MathFormula · "+symbol+" research update")
      .setContentText("Status "+kind+" · open app for current validated evidence. Not a BUY.")
      .setContentIntent(click).setOnlyAlertOnce(true).setAutoCancel(true)
      .setCategory(Notification.CATEGORY_STATUS).build();
    NotificationManager n=getSystemService(NotificationManager.class);
    if(n!=null){
      n.notify(Math.abs(eventKey.hashCode()),note);
      p.edit().putBoolean(slot,true).putLong("last_push_ms",now).apply();
      new WatchStore(this).audit("PUSH",symbol,"RECEIVED",kind,
        "Remote research update delivered · full current-state sync required",0);
    }
  }
}
