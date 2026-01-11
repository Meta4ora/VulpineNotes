package com.example.vulpinenotes.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager as SystemNotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.vulpinenotes.MainActivity
import com.example.vulpinenotes.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationManager(
    private val context: Context,
    private val bookRepository: BookRepository
) {

    companion object {
        const val CHANNEL_ID = "reminder_channel"
        const val NOTIFICATION_ID = 1001
        const val REQUEST_CODE = 1002
        const val ACTION_SHOW_NOTIFICATION = "com.example.vulpinenotes.SHOW_NOTIFICATION"
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Напоминания"
            val description = "Напоминания о книгах для написания"
            val importance = SystemNotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description
            val systemNotificationManager = context.getSystemService(SystemNotificationManager::class.java)
            systemNotificationManager.createNotificationChannel(channel)
        }
    }

    fun schedule(settings: NotificationSettings) {
        cancel()

        if (!settings.isEnabled || settings.selectedBookIds.isEmpty()) return

        // Проверяем разрешения для Android 13+
        if (!hasAlarmPermission()) {
            Log.e("NotificationManager", "Нет разрешения на использование будильников")
            return
        }

        val intent = Intent(context, NotificationReceiver::class.java).apply {
            action = ACTION_SHOW_NOTIFICATION
            putExtra("book_ids", settings.selectedBookIds.toTypedArray())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAt = System.currentTimeMillis() + settings.interval.millis

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            Log.d("NotificationManager", "Уведомление запланировано на: $triggerAt")
        } catch (e: SecurityException) {
            Log.e("NotificationManager", "SecurityException при планировании уведомления", e)
        } catch (e: Exception) {
            Log.e("NotificationManager", "Ошибка при планировании уведомления", e)
        }
    }

    fun cancel() {
        val intent = Intent(context, NotificationReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(pendingIntent)
        } catch (e: SecurityException) {
            Log.e("NotificationManager", "SecurityException при отмене уведомления", e)
        }
    }

    fun show(bookIds: List<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            // Проверяем разрешение на уведомления для Android 13+
            if (!hasNotificationPermission()) {
                Log.e("NotificationManager", "Нет разрешения на отправку уведомлений")
                return@launch
            }

            val books = bookRepository.getBooksByIds(bookIds)
            if (books.isEmpty()) return@launch

            val titles = books.joinToString(", ") { it.title }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val largeIcon = BitmapFactory.decodeResource(
                context.resources,
                R.drawable.ic_notifications_large
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notifications)
                .setLargeIcon(largeIcon)
                .setContentTitle("Пора писать ✍️")
                .setContentText("Не забудьте: $titles")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            try {
                if (notificationManager.areNotificationsEnabled()) {
                    notificationManager.notify(NOTIFICATION_ID, notification)
                }
            } catch (e: SecurityException) {
                Log.e("NotificationManager", "SecurityException при показе уведомления", e)
            }
        }
    }

    private fun hasAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.canScheduleExactAlarms()
            } catch (e: Exception) {
                false
            }
        } else {
            true // Для версий ниже Android 12 разрешение не требуется
        }
    }

    // Проверка разрешения на уведомления (Android 13+)
    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Для версий ниже Android 13 разрешение не требуется
        }
    }
}


class NotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NotificationManager.ACTION_SHOW_NOTIFICATION) return

        val bookIds = intent.getStringArrayExtra("book_ids")?.toList() ?: return

        val database = AppDatabase.getDatabase(context)
        val firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
        val storageDir = context.filesDir.resolve("covers").apply { mkdirs() }

        val repository = BookRepository(
            database.bookDao(),
            database.chapterDao(),
            firestore,
            storageDir
        )

        NotificationManager(context, repository).show(bookIds)
    }
}