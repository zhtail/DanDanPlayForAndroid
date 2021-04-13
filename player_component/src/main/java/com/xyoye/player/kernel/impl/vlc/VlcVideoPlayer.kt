package com.xyoye.player.kernel.impl.vlc

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import android.view.Surface
import com.xyoye.data_component.bean.VideoTrackBean
import com.xyoye.player.kernel.inter.AbstractVideoPlayer
import com.xyoye.player.utils.PlayerConstant
import com.xyoye.player.utils.TrackHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.abs

/**
 * Created by xyoye on 2021/4/12.
 */

class VlcVideoPlayer(private val mContext: Context) : AbstractVideoPlayer() {

    companion object {
        @Volatile
        var playbackState = PlaybackStateCompat.STATE_NONE
            private set
    }

    private lateinit var libVlc: LibVLC
    private lateinit var mMediaPlayer: MediaPlayer
    private lateinit var mMedia: Media

    private val progress = Progress()
    private var lastTime = 0L
    private var seekable = true

    override fun initPlayer() {
        setOptions()
        mMediaPlayer = MediaPlayer(libVlc)
        initVLCEventListener()
    }

    override fun setDataSource(path: String, headers: Map<String, String>?) {
        if (path.isEmpty()) {
            mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_URL_EMPTY, 0)
            return
        }

        mMedia = Media(libVlc, path)
        progress.duration = mMedia.duration
        mMediaPlayer.media = mMedia
        mMedia.release()
    }

    override fun setSurface(surface: Surface) {

    }

    override fun prepareAsync() {
        mMediaPlayer.play()
    }

    override fun start() {
        mMediaPlayer.play()
    }

    override fun pause() {
        mMediaPlayer.pause()
    }

    override fun stop() {
        playbackState = PlaybackStateCompat.STATE_STOPPED
        progress.release()
        lastTime = 0

        mMediaPlayer.stop()
    }

    override fun reset() {

    }

    override fun release() {
        stop()
        mMediaPlayer.setEventListener(null)
        if (isVideoPlaying()) {
            mMediaPlayer.vlcVout.detachViews()
        }
        mMediaPlayer.media?.apply {
            setEventListener(null)
            release()
        }
        GlobalScope.launch(Dispatchers.IO) {
            mMediaPlayer.release()
        }
    }

    override fun seekTo(timeMs: Long) {
        if (seekable && isPlayerAvailable()) {
            mMediaPlayer.time = timeMs
        }
    }

    override fun setSpeed(speed: Float) {
        mMediaPlayer.rate = speed
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        val volume = ((leftVolume + rightVolume) / 2 * 100).toInt()
        mMediaPlayer.volume = volume
    }

    override fun setLooping(isLooping: Boolean) {

    }

    override fun setOptions() {
        val vlcArguments = arrayListOf<String>()
        vlcArguments.add("-vvv")

        libVlc = LibVLC(mContext, vlcArguments)
    }

    override fun selectTrack(select: VideoTrackBean?, deselect: VideoTrackBean?) {
        if (select != null && isPlayerAvailable()){
            if (select.isAudio) {
                mMediaPlayer.audioTrack = select.trackId
            } else {
                mMediaPlayer.spuTrack = select.trackId
            }
        }
    }

    override fun isPlaying(): Boolean {
        return mMediaPlayer.isPlaying
    }

    override fun getCurrentPosition(): Long {
        return progress.position
    }

    override fun getDuration(): Long {
        return progress.duration
    }

    override fun getSpeed(): Float {
        return mMediaPlayer.rate
    }

    override fun getBufferedPercentage(): Int {
        return 0
    }

    override fun getTcpSpeed(): Long {
        return 0
    }

    fun attachRenderView(vlcVideoLayout: VLCVideoLayout) {
        mMediaPlayer.attachViews(vlcVideoLayout, null, true, false)
    }

    fun setScale(scale: MediaPlayer.ScaleType) {
        mMediaPlayer.videoScale = scale
    }

    private fun initVLCEventListener() {
        mMediaPlayer.setEventListener {
            when (it.type) {
                MediaPlayer.Event.Buffering -> {
                    if (it.buffering == 100f) {
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_END, 0)
                    } else {
                        mPlayerEventListener.onInfo(PlayerConstant.MEDIA_INFO_BUFFERING_START, 0)
                    }
                }
                MediaPlayer.Event.Opening -> {
                    mPlayerEventListener.onInfo(
                        PlayerConstant.MEDIA_INFO_VIDEO_RENDERING_START,
                        0
                    )
                }
                MediaPlayer.Event.Playing -> playbackState = PlaybackStateCompat.STATE_PLAYING
                MediaPlayer.Event.Paused -> playbackState = PlaybackStateCompat.STATE_PAUSED
                MediaPlayer.Event.SeekableChanged -> seekable = it.seekable
                MediaPlayer.Event.EncounteredError -> stop()
                MediaPlayer.Event.LengthChanged -> progress.duration = it.lengthChanged
                MediaPlayer.Event.TimeChanged -> {
                    val currentTime = it.timeChanged
                    if (abs(currentTime - lastTime) > 950L) {
                        progress.position = currentTime
                        lastTime = currentTime
                    }
                }
                MediaPlayer.Event.Vout -> {
                    TrackHelper.initVLCTrack(
                        mMediaPlayer.audioTracks,
                        mMediaPlayer.spuTracks
                    )
                    if (it.voutCount > 0) {
                        mMediaPlayer.updateVideoSurfaces()
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    mPlayerEventListener.onCompletion()
                }
                MediaPlayer.Event.ESSelected -> {
                    if (it.esChangedType == IMedia.Track.Type.Audio) {
                        TrackHelper.selectVLCTrack(true, mMediaPlayer.audioTrack)
                    } else if (it.esChangedType == IMedia.Track.Type.Text) {
                        TrackHelper.selectVLCTrack(false, mMediaPlayer.spuTrack)
                    }
                }
            }
        }
    }

    private fun isPlayerAvailable() = mMediaPlayer.hasMedia() && !mMediaPlayer.isReleased

    private fun isVideoPlaying() =
        !mMediaPlayer.isReleased && mMediaPlayer.vlcVout.areViewsAttached()

    class Progress(var position: Long = 0L, var duration: Long = 0L) {

        fun release() {
            position = 0L
            duration = 0L
        }
    }
}