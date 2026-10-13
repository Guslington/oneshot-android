package com.oneshotchat.android.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.oneshotchat.android.R
import com.oneshotchat.android.crypto.Mentions
import com.oneshotchat.android.net.Mention
import com.oneshotchat.android.ui.MainActivity

object Notifications {
    private const val CHANNEL_MENTIONS = "mentions"
    private const val CHANNEL_SERVICE = "service"
    const val FOREGROUND_ID = 1
    const val EXTRA_ROOM = "room"
    const val EXTRA_MENTIONS = "mentions"

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MENTIONS, "Mentions", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When someone mentions you in a room"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while oshi listens for mentions in the background"
            },
        )
    }

    /** The ongoing notification that keeps the listening service alive. */
    fun foreground(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("oshi")
            .setContentText("listening for mentions")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp(ctx, null))
            .build()

    /** Post a notification for a mention. Message text is untrusted, so it's shown as plain text. */
    @SuppressLint("MissingPermission")
    fun mention(ctx: Context, mention: Mention, selfFingerprint: String, sideFrom: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val room = mention.room?.name
        val author = mention.message.author.name ?: "someone"
        val body = Mentions.render(mention.message.text) { fp ->
            if (fp == selfFingerprint) "@you" else "@" + fp.take(6)
        }
        val publicVersion = NotificationCompat.Builder(ctx, CHANNEL_MENTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("oshi")
            .setContentText("new mention")
            .build()

        val notification = NotificationCompat.Builder(ctx, CHANNEL_MENTIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                when {
                    sideFrom != null -> "side room from $sideFrom"
                    room != null -> "#$room"
                    else -> "mention"
                },
            )
            .setContentText("$author: $body")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$author: $body"))
            // A side room is never joined automatically: its notification opens the mentions
            // feed, where joining is a deliberate tap.
            .setContentIntent(if (sideFrom != null) openApp(ctx, null, mentionsFeed = true) else openApp(ctx, room))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()

        val id = mention.message.id.toInt() xor (mention.room?.id?.hashCode() ?: 0)
        NotificationManagerCompat.from(ctx).notify(id, notification)
    }

    private fun openApp(ctx: Context, room: String?, mentionsFeed: Boolean = false): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            room?.let { putExtra(EXTRA_ROOM, it) }
            if (mentionsFeed) putExtra(EXTRA_MENTIONS, true)
        }
        return PendingIntent.getActivity(
            ctx,
            room.orEmpty().hashCode() + if (mentionsFeed) 1 else 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
