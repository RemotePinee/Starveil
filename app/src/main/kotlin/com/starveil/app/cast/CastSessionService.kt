package com.starveil.app.cast

import android.app.Notification
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import androidx.annotation.RequiresApi
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.starveil.app.MainActivity
import com.starveil.app.R
import com.starveil.core.model.CastUiStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 投屏会话的前台服务。
 *
 * 为什么必须有它：投屏状态由进程内存持有，App 一进后台就可能被系统回收——
 * 那样电视还在播，手机端却"忘了"在投屏。前台服务把进程钉住，同时给用户一条
 * 可操作的通知：正在投给谁、暂停/继续、断开。点通知回到 App。
 *
 * 只观察 [CastRepository.status]，不做任何协议操作——协议是 `:core:data` 的事。
 */
class CastSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel()
        observer = scope.launch {
            val repository = (application as? com.starveil.app.StarveilApplication)?.container?.castRepository
            if (repository == null) {
                stopSelf()
                return@launch
            }
            repository.status.collectLatest { status ->
                if (status is CastUiStatus.Connected) {
                    enterForeground(status)
                } else {
                    stopSelf()
                }
            }
        }
    }

    /**
     * 进前台。Android 14 起必须带类型（Manifest 里声明的 mediaPlayback），
     * 并且要有 FOREGROUND_SERVICE / FOREGROUND_SERVICE_MEDIA_PLAYBACK 权限——
     * 缺任何一项都会在投屏刚连上这一帧抛 SecurityException，把进程带崩。
     */
    private fun enterForeground(status: CastUiStatus.Connected) {
        val notification = buildNotification(status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (ignored: SecurityException) {
            // 少数 ROM 会裁掉前台类型或权限。通知没了可以接受，投屏继续，
            // 不能让"刚连上"这一帧崩掉整个进程。
            stopSelf()
        } catch (ignored: IllegalStateException) {
            // Android 12+ 限制从后台启动前台服务时抛它，处理同上。
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: CastUiStatus.Connected): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playing = status.session.state.name == "Playing"
        val toggle = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, CastActionReceiver::class.java).setAction(CastActionReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, CastActionReceiver::class.java).setAction(CastActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(status.session.device.name)
            .setContentText("正在投屏")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, if (playing) "暂停" else "继续", toggle)
            .addAction(0, "断开", stop)
            .build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "投屏会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示正在投屏的设备与操作"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "cast_session"
        private const val NOTIFICATION_ID = 0x0C45

        /** 按当前状态起停服务：连上就常驻，断开就撤掉。 */
        fun sync(context: Context, status: CastUiStatus) {
            val intent = Intent(context, CastSessionService::class.java)
            if (status !is CastUiStatus.Connected) {
                context.stopService(intent)
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (ignored: IllegalStateException) {
                // Android 12+ 在后台不允许起前台服务；投屏本身不受影响，
                // 只是没有常驻通知，不能因为这一下闪退。
            }
        }
    }
}
