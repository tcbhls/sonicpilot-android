package com.sonicpilot.music.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sonicpilot.music.MainActivity
import com.sonicpilot.music.SonicPilotApp

/**
 * AndroidX Media3 后台音乐播放服务
 * - 调优低延迟快速起播缓冲策略（0.4s 即开播），兼顾 FLAC 无损流畅播放与瞬时响应
 * - 通过 ForwardingPlayer 完整接管锁屏/通知栏/蓝牙耳机/车机的「上一首 / 下一首」切歌事件并路由到 PlayerController 队列
 */
@UnstableApi
class MusicPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val fastLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                10_000, // minBufferMs
                45_000, // maxBufferMs
                400,    // bufferForPlaybackMs（起播只需 0.4 秒缓冲，极速出声！）
                1_000   // bufferForPlaybackAfterRebufferMs
            )
            .build()

        val rawPlayer = ExoPlayer.Builder(this)
            .setLoadControl(fastLoadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 包装 ForwardingPlayer：即使底层 ExoPlayer 当前只装载单条直链，也让通知栏/锁屏/蓝牙耳机正常展示并响应上下曲切歌！
        val queueAwarePlayer = object : ForwardingPlayer(rawPlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true

            override fun seekToNext() {
                runCatching { SonicPilotApp.instance.playerController.nextTrack() }
            }

            override fun seekToNextMediaItem() {
                runCatching { SonicPilotApp.instance.playerController.nextTrack() }
            }

            override fun seekToPrevious() {
                runCatching { SonicPilotApp.instance.playerController.previousTrack() }
            }

            override fun seekToPreviousMediaItem() {
                runCatching { SonicPilotApp.instance.playerController.previousTrack() }
            }
        }

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, queueAwarePlayer)
            .setSessionActivity(openAppIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
