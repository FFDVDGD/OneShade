package io.github.opd2413.ctrlcenter;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/** A debug-only fixture; cleanup touches only this module's own test notifications. */
public final class VerificationActivity extends Activity {
    @Override
    public void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        handleIntent();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent();
    }

    private void handleIntent() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (getIntent().getBooleanExtra("cleanup", false)) {
            notifications.cancelAll();
            finish();
            return;
        }
        if (getIntent().getBooleanExtra("post", false)) {
            notifications.createNotificationChannel(new NotificationChannel(
                    "verification", "双栏交互测试", NotificationManager.IMPORTANCE_LOW));
            int count = getIntent().getIntExtra("count", 8);
            for (int i = 1; i <= count; i++) {
                Intent click = new Intent(this, VerificationActivity.class).putExtra("clicked", i);
                PendingIntent pending = PendingIntent.getActivity(this, i, click,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                Notification notification = new Notification.Builder(this, "verification")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("双栏测试通知 " + i)
                        .setContentText("点击验证跳转；左右划动只清除此测试通知。")
                        .setGroup("verification-" + i)
                        .setContentIntent(pending)
                        .setAutoCancel(true)
                        .build();
                notifications.notify("verification", i, notification);
            }
            finish();
            return;
        }
        int clicked = getIntent().getIntExtra("clicked", -1);
        Log.i("DualShadeVerification", "Clicked notification " + clicked);
        TextView result = new TextView(this);
        result.setText("已打开测试通知 " + clicked);
        result.setTextSize(28);
        result.setTextColor(Color.WHITE);
        result.setBackgroundColor(Color.BLACK);
        result.setPadding(64, 64, 64, 64);
        setContentView(result);
    }
}
