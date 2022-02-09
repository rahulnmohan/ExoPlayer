/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.analytics;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.common.util.Assertions.checkStateNotNull;

import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodec.CodecException;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.Surface;
import androidx.annotation.CallSuper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.DiscontinuityReason;
import androidx.media3.common.Player.PlaybackSuppressionReason;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Period;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.TrackGroupArray;
import androidx.media3.common.TrackSelectionArray;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.TracksInfo;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.ListenerSet;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.drm.DrmSession;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/**
 * Data collector that forwards analytics events to {@link AnalyticsListener AnalyticsListeners}.
 */
@UnstableApi
public class AnalyticsCollector
    implements Player.Listener,
        MediaSourceEventListener,
        BandwidthMeter.EventListener,
        DrmSessionEventListener {

  private final Clock clock;
  private final Period period;
  private final Window window;
  private final MediaPeriodQueueTracker mediaPeriodQueueTracker;
  private final SparseArray<EventTime> eventTimes;

  private ListenerSet<AnalyticsListener> listeners;
  private @MonotonicNonNull Player player;
  private @MonotonicNonNull HandlerWrapper handler;
  private boolean isSeeking;

  /**
   * Creates an analytics collector.
   *
   * @param clock A {@link Clock} used to generate timestamps.
   */
  public AnalyticsCollector(Clock clock) {
    this.clock = checkNotNull(clock);
    listeners = new ListenerSet<>(Util.getCurrentOrMainLooper(), clock, (listener, flags) -> {});
    period = new Period();
    window = new Window();
    mediaPeriodQueueTracker = new MediaPeriodQueueTracker(period);
    eventTimes = new SparseArray<>();
  }

  /**
   * Adds a listener for analytics events.
   *
   * @param listener The listener to add.
   */
  @CallSuper
  public void addListener(AnalyticsListener listener) {
    checkNotNull(listener);
    listeners.add(listener);
  }

  /**
   * Removes a previously added analytics event listener.
   *
   * @param listener The listener to remove.
   */
  @CallSuper
  public void removeListener(AnalyticsListener listener) {
    listeners.remove(listener);
  }

  /**
   * Sets the player for which data will be collected. Must only be called if no player has been set
   * yet or the current player is idle.
   *
   * @param player The {@link Player} for which data will be collected.
   * @param looper The {@link Looper} used for listener callbacks.
   */
  @CallSuper
  public void setPlayer(Player player, Looper looper) {
    checkState(this.player == null || mediaPeriodQueueTracker.mediaPeriodQueue.isEmpty());
    this.player = checkNotNull(player);
    handler = clock.createHandler(looper, null);
    listeners =
        listeners.copy(
            looper,
            (listener, flags) ->
                listener.onEvents(player, new AnalyticsListener.Events(flags, eventTimes)));
  }

  /**
   * Releases the collector. Must be called after the player for which data is collected has been
   * released.
   */
  @CallSuper
  public void release() {
    // Release lazily so that all events that got triggered as part of player.release()
    // are still delivered to all listeners and onPlayerReleased() is delivered last.
    checkStateNotNull(handler).post(this::releaseInternal);
  }

  /**
   * Updates the playback queue information used for event association.
   *
   * <p>Should only be called by the player controlling the queue and not from app code.
   *
   * @param queue The playback queue of media periods identified by their {@link MediaPeriodId}.
   * @param readingPeriod The media period in the queue that is currently being read by renderers,
   *     or null if the queue is empty.
   */
  public final void updateMediaPeriodQueueInfo(
      List<MediaPeriodId> queue, @Nullable MediaPeriodId readingPeriod) {
    mediaPeriodQueueTracker.onQueueUpdated(queue, readingPeriod, checkNotNull(player));
  }

  // External events.

  /**
   * Notify analytics collector that a seek operation will start. Should be called before the player
   * adjusts its state and position to the seek.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void notifySeekStarted() {
    if (!isSeeking) {
      EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
      isSeeking = true;
      sendEvent(
          eventTime, /* eventFlag= */ C.INDEX_UNSET, listener -> listener.onSeekStarted(eventTime));
    }
  }

  // Audio events.

  /**
   * Called when the audio renderer is enabled.
   *
   * @param counters {@link DecoderCounters} that will be updated by the audio renderer for as long
   *     as it remains enabled.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onAudioEnabled(DecoderCounters counters) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_ENABLED,
        listener -> {
          listener.onAudioEnabled(eventTime, counters);
          listener.onDecoderEnabled(eventTime, C.TRACK_TYPE_AUDIO, counters);
        });
  }

  /**
   * Called when a audio decoder is created.
   *
   * @param decoderName The audio decoder that was created.
   * @param initializedTimestampMs {@link SystemClock#elapsedRealtime()} when initialization
   *     finished.
   * @param initializationDurationMs The time taken to initialize the decoder in milliseconds.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onAudioDecoderInitialized(
      String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_DECODER_INITIALIZED,
        listener -> {
          listener.onAudioDecoderInitialized(eventTime, decoderName, initializationDurationMs);
          listener.onAudioDecoderInitialized(
              eventTime, decoderName, initializedTimestampMs, initializationDurationMs);
          listener.onDecoderInitialized(
              eventTime, C.TRACK_TYPE_AUDIO, decoderName, initializationDurationMs);
        });
  }

  /**
   * Called when the format of the media being consumed by the audio renderer changes.
   *
   * @param format The new format.
   * @param decoderReuseEvaluation The result of the evaluation to determine whether an existing
   *     decoder instance can be reused for the new format, or {@code null} if the renderer did not
   *     have a decoder.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onAudioInputFormatChanged(
      Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_INPUT_FORMAT_CHANGED,
        listener -> {
          listener.onAudioInputFormatChanged(eventTime, format);
          listener.onAudioInputFormatChanged(eventTime, format, decoderReuseEvaluation);
          listener.onDecoderInputFormatChanged(eventTime, C.TRACK_TYPE_AUDIO, format);
        });
  }

  /**
   * Called when the audio position has increased for the first time since the last pause or
   * position reset.
   *
   * @param playoutStartSystemTimeMs The approximate derived {@link System#currentTimeMillis()} at
   *     which playout started.
   */
  public final void onAudioPositionAdvancing(long playoutStartSystemTimeMs) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_POSITION_ADVANCING,
        listener -> listener.onAudioPositionAdvancing(eventTime, playoutStartSystemTimeMs));
  }

  /**
   * Called when an audio underrun occurs.
   *
   * @param bufferSize The size of the audio output buffer, in bytes.
   * @param bufferSizeMs The size of the audio output buffer, in milliseconds, if it contains PCM
   *     encoded audio. {@link C#TIME_UNSET} if the output buffer contains non-PCM encoded audio.
   * @param elapsedSinceLastFeedMs The time since audio was last written to the output buffer.
   */
  public final void onAudioUnderrun(
      int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_UNDERRUN,
        listener ->
            listener.onAudioUnderrun(eventTime, bufferSize, bufferSizeMs, elapsedSinceLastFeedMs));
  }

  /**
   * Called when a audio decoder is released.
   *
   * @param decoderName The audio decoder that was released.
   */
  public final void onAudioDecoderReleased(String decoderName) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_DECODER_RELEASED,
        listener -> listener.onAudioDecoderReleased(eventTime, decoderName));
  }

  /**
   * Called when the audio renderer is disabled.
   *
   * @param counters {@link DecoderCounters} that were updated by the audio renderer.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onAudioDisabled(DecoderCounters counters) {
    EventTime eventTime = generatePlayingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_DISABLED,
        listener -> {
          listener.onAudioDisabled(eventTime, counters);
          listener.onDecoderDisabled(eventTime, C.TRACK_TYPE_AUDIO, counters);
        });
  }

  /**
   * Called when {@link AudioSink} has encountered an error.
   *
   * <p>If the sink writes to a platform {@link AudioTrack}, this will be called for all {@link
   * AudioTrack} errors.
   *
   * @param audioSinkError The error that occurred. Typically an {@link
   *     AudioSink.InitializationException}, a {@link AudioSink.WriteException}, or an {@link
   *     AudioSink.UnexpectedDiscontinuityException}.
   */
  public final void onAudioSinkError(Exception audioSinkError) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_SINK_ERROR,
        listener -> listener.onAudioSinkError(eventTime, audioSinkError));
  }

  /**
   * Called when an audio decoder encounters an error.
   *
   * @param audioCodecError The error. Typically a {@link CodecException} if the renderer uses
   *     {@link MediaCodec}, or a {@link DecoderException} if the renderer uses a software decoder.
   */
  public final void onAudioCodecError(Exception audioCodecError) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_CODEC_ERROR,
        listener -> listener.onAudioCodecError(eventTime, audioCodecError));
  }

  /**
   * Called when the volume changes.
   *
   * @param volume The new volume, with 0 being silence and 1 being unity gain.
   */
  public final void onVolumeChanged(float volume) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VOLUME_CHANGED,
        listener -> listener.onVolumeChanged(eventTime, volume));
  }

  // Video events.

  /**
   * Called when the video renderer is enabled.
   *
   * @param counters {@link DecoderCounters} that will be updated by the video renderer for as long
   *     as it remains enabled.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onVideoEnabled(DecoderCounters counters) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_ENABLED,
        listener -> {
          listener.onVideoEnabled(eventTime, counters);
          listener.onDecoderEnabled(eventTime, C.TRACK_TYPE_VIDEO, counters);
        });
  }

  /**
   * Called when a video decoder is created.
   *
   * @param decoderName The decoder that was created.
   * @param initializedTimestampMs {@link SystemClock#elapsedRealtime()} when initialization
   *     finished.
   * @param initializationDurationMs The time taken to initialize the decoder in milliseconds.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onVideoDecoderInitialized(
      String decoderName, long initializedTimestampMs, long initializationDurationMs) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_DECODER_INITIALIZED,
        listener -> {
          listener.onVideoDecoderInitialized(eventTime, decoderName, initializationDurationMs);
          listener.onVideoDecoderInitialized(
              eventTime, decoderName, initializedTimestampMs, initializationDurationMs);
          listener.onDecoderInitialized(
              eventTime, C.TRACK_TYPE_VIDEO, decoderName, initializationDurationMs);
        });
  }

  /**
   * Called when the format of the media being consumed by the video renderer changes.
   *
   * @param format The new format.
   * @param decoderReuseEvaluation The result of the evaluation to determine whether an existing
   *     decoder instance can be reused for the new format, or {@code null} if the renderer did not
   *     have a decoder.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onVideoInputFormatChanged(
      Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_INPUT_FORMAT_CHANGED,
        listener -> {
          listener.onVideoInputFormatChanged(eventTime, format);
          listener.onVideoInputFormatChanged(eventTime, format, decoderReuseEvaluation);
          listener.onDecoderInputFormatChanged(eventTime, C.TRACK_TYPE_VIDEO, format);
        });
  }

  /**
   * Called to report the number of frames dropped by the video renderer. Dropped frames are
   * reported whenever the renderer is stopped having dropped frames, and optionally, whenever the
   * count reaches a specified threshold whilst the renderer is started.
   *
   * @param count The number of dropped frames.
   * @param elapsedMs The duration in milliseconds over which the frames were dropped. This duration
   *     is timed from when the renderer was started or from when dropped frames were last reported
   *     (whichever was more recent), and not from when the first of the reported drops occurred.
   */
  public final void onDroppedFrames(int count, long elapsedMs) {
    EventTime eventTime = generatePlayingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DROPPED_VIDEO_FRAMES,
        listener -> listener.onDroppedVideoFrames(eventTime, count, elapsedMs));
  }

  /**
   * Called when a video decoder is released.
   *
   * @param decoderName The video decoder that was released.
   */
  public final void onVideoDecoderReleased(String decoderName) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_DECODER_RELEASED,
        listener -> listener.onVideoDecoderReleased(eventTime, decoderName));
  }

  /**
   * Called when the video renderer is disabled.
   *
   * @param counters {@link DecoderCounters} that were updated by the video renderer.
   */
  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  public final void onVideoDisabled(DecoderCounters counters) {
    EventTime eventTime = generatePlayingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_DISABLED,
        listener -> {
          listener.onVideoDisabled(eventTime, counters);
          listener.onDecoderDisabled(eventTime, C.TRACK_TYPE_VIDEO, counters);
        });
  }

  /**
   * Called when a frame is rendered for the first time since setting the output, or since the
   * renderer was reset, or since the stream being rendered was changed.
   *
   * @param output The output of the video renderer. Normally a {@link Surface}, however some video
   *     renderers may have other output types (e.g., a {@link VideoDecoderOutputBufferRenderer}).
   * @param renderTimeMs The {@link SystemClock#elapsedRealtime()} when the frame was rendered.
   */
  public final void onRenderedFirstFrame(Object output, long renderTimeMs) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_RENDERED_FIRST_FRAME,
        listener -> listener.onRenderedFirstFrame(eventTime, output, renderTimeMs));
  }

  /**
   * Called to report the video processing offset of video frames processed by the video renderer.
   *
   * <p>Video processing offset represents how early a video frame is processed compared to the
   * player's current position. For each video frame, the offset is calculated as <em>P<sub>vf</sub>
   * - P<sub>pl</sub></em> where <em>P<sub>vf</sub></em> is the presentation timestamp of the video
   * frame and <em>P<sub>pl</sub></em> is the current position of the player. Positive values
   * indicate the frame was processed early enough whereas negative values indicate that the
   * player's position had progressed beyond the frame's timestamp when the frame was processed (and
   * the frame was probably dropped).
   *
   * <p>The renderer reports the sum of video processing offset samples (one sample per processed
   * video frame: dropped, skipped or rendered) and the total number of samples.
   *
   * @param totalProcessingOffsetUs The sum of all video frame processing offset samples for the
   *     video frames processed by the renderer in microseconds.
   * @param frameCount The number of samples included in the {@code totalProcessingOffsetUs}.
   */
  public final void onVideoFrameProcessingOffset(long totalProcessingOffsetUs, int frameCount) {
    EventTime eventTime = generatePlayingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_FRAME_PROCESSING_OFFSET,
        listener ->
            listener.onVideoFrameProcessingOffset(eventTime, totalProcessingOffsetUs, frameCount));
  }

  /**
   * Called when a video decoder encounters an error.
   *
   * <p>This method being called does not indicate that playback has failed, or that it will fail.
   * The player may be able to recover from the error. Hence applications should <em>not</em>
   * implement this method to display a user visible error or initiate an application level retry.
   * {@link Player.Listener#onPlayerError} is the appropriate place to implement such behavior. This
   * method is called to provide the application with an opportunity to log the error if it wishes
   * to do so.
   *
   * @param videoCodecError The error. Typically a {@link CodecException} if the renderer uses
   *     {@link MediaCodec}, or a {@link DecoderException} if the renderer uses a software decoder.
   */
  public final void onVideoCodecError(Exception videoCodecError) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_CODEC_ERROR,
        listener -> listener.onVideoCodecError(eventTime, videoCodecError));
  }

  /**
   * Called each time there's a change in the size of the surface onto which the video is being
   * rendered.
   *
   * @param width The surface width in pixels. May be {@link C#LENGTH_UNSET} if unknown, or 0 if the
   *     video is not rendered onto a surface.
   * @param height The surface height in pixels. May be {@link C#LENGTH_UNSET} if unknown, or 0 if
   *     the video is not rendered onto a surface.
   */
  public void onSurfaceSizeChanged(int width, int height) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_SURFACE_SIZE_CHANGED,
        listener -> listener.onSurfaceSizeChanged(eventTime, width, height));
  }

  // MediaSourceEventListener implementation.

  @Override
  public final void onLoadStarted(
      int windowIndex,
      @Nullable MediaPeriodId mediaPeriodId,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_LOAD_STARTED,
        listener -> listener.onLoadStarted(eventTime, loadEventInfo, mediaLoadData));
  }

  @Override
  public final void onLoadCompleted(
      int windowIndex,
      @Nullable MediaPeriodId mediaPeriodId,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_LOAD_COMPLETED,
        listener -> listener.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData));
  }

  @Override
  public final void onLoadCanceled(
      int windowIndex,
      @Nullable MediaPeriodId mediaPeriodId,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_LOAD_CANCELED,
        listener -> listener.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData));
  }

  @Override
  public final void onLoadError(
      int windowIndex,
      @Nullable MediaPeriodId mediaPeriodId,
      LoadEventInfo loadEventInfo,
      MediaLoadData mediaLoadData,
      IOException error,
      boolean wasCanceled) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_LOAD_ERROR,
        listener ->
            listener.onLoadError(eventTime, loadEventInfo, mediaLoadData, error, wasCanceled));
  }

  @Override
  public final void onUpstreamDiscarded(
      int windowIndex, @Nullable MediaPeriodId mediaPeriodId, MediaLoadData mediaLoadData) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_UPSTREAM_DISCARDED,
        listener -> listener.onUpstreamDiscarded(eventTime, mediaLoadData));
  }

  @Override
  public final void onDownstreamFormatChanged(
      int windowIndex, @Nullable MediaPeriodId mediaPeriodId, MediaLoadData mediaLoadData) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DOWNSTREAM_FORMAT_CHANGED,
        listener -> listener.onDownstreamFormatChanged(eventTime, mediaLoadData));
  }

  // Player.Listener implementation.

  // TODO: Use Player.Listener.onEvents to know when a set of simultaneous callbacks finished.
  // This helps to assign exactly the same EventTime to all of them instead of having slightly
  // different real times.

  @Override
  public final void onTimelineChanged(Timeline timeline, @Player.TimelineChangeReason int reason) {
    mediaPeriodQueueTracker.onTimelineChanged(checkNotNull(player));
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_TIMELINE_CHANGED,
        listener -> listener.onTimelineChanged(eventTime, reason));
  }

  @Override
  public final void onMediaItemTransition(
      @Nullable MediaItem mediaItem, @Player.MediaItemTransitionReason int reason) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_MEDIA_ITEM_TRANSITION,
        listener -> listener.onMediaItemTransition(eventTime, mediaItem, reason));
  }

  @Override
  @SuppressWarnings("deprecation") // Implementing and calling deprecate listener method
  public final void onTracksChanged(
      TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_TRACKS_CHANGED,
        listener -> listener.onTracksChanged(eventTime, trackGroups, trackSelections));
  }

  @Override
  public void onTracksInfoChanged(TracksInfo tracksInfo) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_TRACKS_CHANGED,
        listener -> listener.onTracksInfoChanged(eventTime, tracksInfo));
  }

  @SuppressWarnings("deprecation") // Implementing deprecated method.
  @Override
  public void onLoadingChanged(boolean isLoading) {
    // Do nothing. Handled by non-deprecated onIsLoadingChanged.
  }

  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  @Override
  public final void onIsLoadingChanged(boolean isLoading) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_IS_LOADING_CHANGED,
        listener -> {
          listener.onLoadingChanged(eventTime, isLoading);
          listener.onIsLoadingChanged(eventTime, isLoading);
        });
  }

  @Override
  public void onAvailableCommandsChanged(Player.Commands availableCommands) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AVAILABLE_COMMANDS_CHANGED,
        listener -> listener.onAvailableCommandsChanged(eventTime, availableCommands));
  }

  @SuppressWarnings("deprecation") // Implementing and calling deprecated listener method.
  @Override
  public final void onPlayerStateChanged(boolean playWhenReady, @Player.State int playbackState) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        /* eventFlag= */ C.INDEX_UNSET,
        listener -> listener.onPlayerStateChanged(eventTime, playWhenReady, playbackState));
  }

  @Override
  public final void onPlaybackStateChanged(@Player.State int playbackState) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED,
        listener -> listener.onPlaybackStateChanged(eventTime, playbackState));
  }

  @Override
  public final void onPlayWhenReadyChanged(
      boolean playWhenReady, @Player.PlayWhenReadyChangeReason int reason) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAY_WHEN_READY_CHANGED,
        listener -> listener.onPlayWhenReadyChanged(eventTime, playWhenReady, reason));
  }

  @Override
  public final void onPlaybackSuppressionReasonChanged(
      @PlaybackSuppressionReason int playbackSuppressionReason) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
        listener ->
            listener.onPlaybackSuppressionReasonChanged(eventTime, playbackSuppressionReason));
  }

  @Override
  public void onIsPlayingChanged(boolean isPlaying) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_IS_PLAYING_CHANGED,
        listener -> listener.onIsPlayingChanged(eventTime, isPlaying));
  }

  @Override
  public final void onRepeatModeChanged(@Player.RepeatMode int repeatMode) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_REPEAT_MODE_CHANGED,
        listener -> listener.onRepeatModeChanged(eventTime, repeatMode));
  }

  @Override
  public final void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
        listener -> listener.onShuffleModeChanged(eventTime, shuffleModeEnabled));
  }

  @Override
  public final void onPlayerError(PlaybackException error) {
    EventTime eventTime = getEventTimeForErrorEvent(error);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYER_ERROR,
        listener -> listener.onPlayerError(eventTime, error));
  }

  @Override
  public void onPlayerErrorChanged(@Nullable PlaybackException error) {
    EventTime eventTime = getEventTimeForErrorEvent(error);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYER_ERROR,
        listener -> listener.onPlayerErrorChanged(eventTime, error));
  }

  @SuppressWarnings("deprecation") // Implementing deprecated method.
  @Override
  public void onPositionDiscontinuity(@DiscontinuityReason int reason) {
    // Do nothing. Handled by non-deprecated onPositionDiscontinuity.
  }

  // Calling deprecated callback.
  @SuppressWarnings("deprecation")
  @Override
  public final void onPositionDiscontinuity(
      Player.PositionInfo oldPosition,
      Player.PositionInfo newPosition,
      @Player.DiscontinuityReason int reason) {
    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
      isSeeking = false;
    }
    mediaPeriodQueueTracker.onPositionDiscontinuity(checkNotNull(player));
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_POSITION_DISCONTINUITY,
        listener -> {
          listener.onPositionDiscontinuity(eventTime, reason);
          listener.onPositionDiscontinuity(eventTime, oldPosition, newPosition, reason);
        });
  }

  @Override
  public final void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYBACK_PARAMETERS_CHANGED,
        listener -> listener.onPlaybackParametersChanged(eventTime, playbackParameters));
  }

  @Override
  public void onSeekBackIncrementChanged(long seekBackIncrementMs) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_SEEK_BACK_INCREMENT_CHANGED,
        listener -> listener.onSeekBackIncrementChanged(eventTime, seekBackIncrementMs));
  }

  @Override
  public void onSeekForwardIncrementChanged(long seekForwardIncrementMs) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_SEEK_FORWARD_INCREMENT_CHANGED,
        listener -> listener.onSeekForwardIncrementChanged(eventTime, seekForwardIncrementMs));
  }

  @Override
  public void onMaxSeekToPreviousPositionChanged(long maxSeekToPreviousPositionMs) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_MAX_SEEK_TO_PREVIOUS_POSITION_CHANGED,
        listener ->
            listener.onMaxSeekToPreviousPositionChanged(eventTime, maxSeekToPreviousPositionMs));
  }

  @Override
  public void onMediaMetadataChanged(MediaMetadata mediaMetadata) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_MEDIA_METADATA_CHANGED,
        listener -> listener.onMediaMetadataChanged(eventTime, mediaMetadata));
  }

  @Override
  public void onPlaylistMetadataChanged(MediaMetadata playlistMetadata) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYLIST_METADATA_CHANGED,
        listener -> listener.onPlaylistMetadataChanged(eventTime, playlistMetadata));
  }

  @Override
  public final void onMetadata(Metadata metadata) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_METADATA,
        listener -> listener.onMetadata(eventTime, metadata));
  }

  @Override
  public void onCues(List<Cue> cues) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime, AnalyticsListener.EVENT_CUES, listener -> listener.onCues(eventTime, cues));
  }

  @SuppressWarnings("deprecation") // Implementing and calling deprecated listener method.
  @Override
  public final void onSeekProcessed() {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime, /* eventFlag= */ C.INDEX_UNSET, listener -> listener.onSeekProcessed(eventTime));
  }

  @Override
  public final void onSkipSilenceEnabledChanged(boolean skipSilenceEnabled) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_SKIP_SILENCE_ENABLED_CHANGED,
        listener -> listener.onSkipSilenceEnabledChanged(eventTime, skipSilenceEnabled));
  }

  @Override
  public final void onAudioSessionIdChanged(int audioSessionId) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_SESSION_ID,
        listener -> listener.onAudioSessionIdChanged(eventTime, audioSessionId));
  }

  @Override
  public final void onAudioAttributesChanged(AudioAttributes audioAttributes) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_AUDIO_ATTRIBUTES_CHANGED,
        listener -> listener.onAudioAttributesChanged(eventTime, audioAttributes));
  }

  @SuppressWarnings("deprecation") // Calling deprecated listener method.
  @Override
  public final void onVideoSizeChanged(VideoSize videoSize) {
    EventTime eventTime = generateReadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_VIDEO_SIZE_CHANGED,
        listener -> {
          listener.onVideoSizeChanged(eventTime, videoSize);
          listener.onVideoSizeChanged(
              eventTime,
              videoSize.width,
              videoSize.height,
              videoSize.unappliedRotationDegrees,
              videoSize.pixelWidthHeightRatio);
        });
  }

  @Override
  public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
        listener -> listener.onTrackSelectionParametersChanged(eventTime, parameters));
  }

  @Override
  public void onDeviceInfoChanged(DeviceInfo deviceInfo) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DEVICE_INFO_CHANGED,
        listener -> listener.onDeviceInfoChanged(eventTime, deviceInfo));
  }

  @Override
  public void onDeviceVolumeChanged(int volume, boolean muted) {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DEVICE_VOLUME_CHANGED,
        listener -> listener.onDeviceVolumeChanged(eventTime, volume, muted));
  }

  @SuppressWarnings("UngroupedOverloads") // Grouped by interface.
  @Override
  public void onRenderedFirstFrame() {
    // Do nothing. Handled by onRenderedFirstFrame call with additional parameters.
  }

  @Override
  public void onEvents(Player player, Player.Events events) {
    // Do nothing. AnalyticsCollector issues its own onEvents.
  }

  // BandwidthMeter.EventListener implementation.

  @Override
  public final void onBandwidthSample(int elapsedMs, long bytesTransferred, long bitrateEstimate) {
    EventTime eventTime = generateLoadingMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_BANDWIDTH_ESTIMATE,
        listener ->
            listener.onBandwidthEstimate(eventTime, elapsedMs, bytesTransferred, bitrateEstimate));
  }

  // DrmSessionEventListener implementation.

  @Override
  @SuppressWarnings("deprecation") // Calls deprecated listener method.
  public final void onDrmSessionAcquired(
      int windowIndex, @Nullable MediaPeriodId mediaPeriodId, @DrmSession.State int state) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_SESSION_ACQUIRED,
        listener -> {
          listener.onDrmSessionAcquired(eventTime);
          listener.onDrmSessionAcquired(eventTime, state);
        });
  }

  @Override
  public final void onDrmKeysLoaded(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_KEYS_LOADED,
        listener -> listener.onDrmKeysLoaded(eventTime));
  }

  @Override
  public final void onDrmSessionManagerError(
      int windowIndex, @Nullable MediaPeriodId mediaPeriodId, Exception error) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_SESSION_MANAGER_ERROR,
        listener -> listener.onDrmSessionManagerError(eventTime, error));
  }

  @Override
  public final void onDrmKeysRestored(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_KEYS_RESTORED,
        listener -> listener.onDrmKeysRestored(eventTime));
  }

  @Override
  public final void onDrmKeysRemoved(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_KEYS_REMOVED,
        listener -> listener.onDrmKeysRemoved(eventTime));
  }

  @Override
  public final void onDrmSessionReleased(int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    EventTime eventTime = generateMediaPeriodEventTime(windowIndex, mediaPeriodId);
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_DRM_SESSION_RELEASED,
        listener -> listener.onDrmSessionReleased(eventTime));
  }

  // Internal methods.

  /**
   * Sends an event to registered listeners.
   *
   * @param eventTime The {@link EventTime} to report.
   * @param eventFlag An integer flag indicating the type of the event, or {@link C#INDEX_UNSET} to
   *     report this event without flag.
   * @param eventInvocation The event.
   */
  protected final void sendEvent(
      EventTime eventTime, int eventFlag, ListenerSet.Event<AnalyticsListener> eventInvocation) {
    eventTimes.put(eventFlag, eventTime);
    listeners.sendEvent(eventFlag, eventInvocation);
  }

  /** Generates an {@link EventTime} for the currently playing item in the player. */
  protected final EventTime generateCurrentPlayerMediaPeriodEventTime() {
    return generateEventTime(mediaPeriodQueueTracker.getCurrentPlayerMediaPeriod());
  }

  /** Returns a new {@link EventTime} for the specified timeline, window and media period id. */
  @RequiresNonNull("player")
  protected final EventTime generateEventTime(
      Timeline timeline, int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    if (timeline.isEmpty()) {
      // Ensure media period id is only reported together with a valid timeline.
      mediaPeriodId = null;
    }
    long realtimeMs = clock.elapsedRealtime();
    long eventPositionMs;
    boolean isInCurrentWindow =
        timeline.equals(player.getCurrentTimeline())
            && windowIndex == player.getCurrentMediaItemIndex();
    if (mediaPeriodId != null && mediaPeriodId.isAd()) {
      boolean isCurrentAd =
          isInCurrentWindow
              && player.getCurrentAdGroupIndex() == mediaPeriodId.adGroupIndex
              && player.getCurrentAdIndexInAdGroup() == mediaPeriodId.adIndexInAdGroup;
      // Assume start position of 0 for future ads.
      eventPositionMs = isCurrentAd ? player.getCurrentPosition() : 0;
    } else if (isInCurrentWindow) {
      eventPositionMs = player.getContentPosition();
    } else {
      // Assume default start position for future content windows. If timeline is not available yet,
      // assume start position of 0.
      eventPositionMs =
          timeline.isEmpty() ? 0 : timeline.getWindow(windowIndex, window).getDefaultPositionMs();
    }
    @Nullable
    MediaPeriodId currentMediaPeriodId = mediaPeriodQueueTracker.getCurrentPlayerMediaPeriod();
    return new EventTime(
        realtimeMs,
        timeline,
        windowIndex,
        mediaPeriodId,
        eventPositionMs,
        player.getCurrentTimeline(),
        player.getCurrentMediaItemIndex(),
        currentMediaPeriodId,
        player.getCurrentPosition(),
        player.getTotalBufferedDuration());
  }

  private void releaseInternal() {
    EventTime eventTime = generateCurrentPlayerMediaPeriodEventTime();
    sendEvent(
        eventTime,
        AnalyticsListener.EVENT_PLAYER_RELEASED,
        listener -> listener.onPlayerReleased(eventTime));
    listeners.release();
  }

  private EventTime generateEventTime(@Nullable MediaPeriodId mediaPeriodId) {
    checkNotNull(player);
    @Nullable
    Timeline knownTimeline =
        mediaPeriodId == null
            ? null
            : mediaPeriodQueueTracker.getMediaPeriodIdTimeline(mediaPeriodId);
    if (mediaPeriodId == null || knownTimeline == null) {
      int windowIndex = player.getCurrentMediaItemIndex();
      Timeline timeline = player.getCurrentTimeline();
      boolean windowIsInTimeline = windowIndex < timeline.getWindowCount();
      return generateEventTime(
          windowIsInTimeline ? timeline : Timeline.EMPTY, windowIndex, /* mediaPeriodId= */ null);
    }
    int windowIndex = knownTimeline.getPeriodByUid(mediaPeriodId.periodUid, period).windowIndex;
    return generateEventTime(knownTimeline, windowIndex, mediaPeriodId);
  }

  private EventTime generatePlayingMediaPeriodEventTime() {
    return generateEventTime(mediaPeriodQueueTracker.getPlayingMediaPeriod());
  }

  private EventTime generateReadingMediaPeriodEventTime() {
    return generateEventTime(mediaPeriodQueueTracker.getReadingMediaPeriod());
  }

  private EventTime generateLoadingMediaPeriodEventTime() {
    return generateEventTime(mediaPeriodQueueTracker.getLoadingMediaPeriod());
  }

  private EventTime generateMediaPeriodEventTime(
      int windowIndex, @Nullable MediaPeriodId mediaPeriodId) {
    checkNotNull(player);
    if (mediaPeriodId != null) {
      boolean isInKnownTimeline =
          mediaPeriodQueueTracker.getMediaPeriodIdTimeline(mediaPeriodId) != null;
      return isInKnownTimeline
          ? generateEventTime(mediaPeriodId)
          : generateEventTime(Timeline.EMPTY, windowIndex, mediaPeriodId);
    }
    Timeline timeline = player.getCurrentTimeline();
    boolean windowIsInTimeline = windowIndex < timeline.getWindowCount();
    return generateEventTime(
        windowIsInTimeline ? timeline : Timeline.EMPTY, windowIndex, /* mediaPeriodId= */ null);
  }

  private EventTime getEventTimeForErrorEvent(@Nullable PlaybackException error) {
    if (error instanceof ExoPlaybackException) {
      ExoPlaybackException exoError = (ExoPlaybackException) error;
      if (exoError.mediaPeriodId != null) {
        return generateEventTime(new MediaPeriodId(exoError.mediaPeriodId));
      }
    }
    return generateCurrentPlayerMediaPeriodEventTime();
  }

  /** Keeps track of the active media periods and currently playing and reading media period. */
  private static final class MediaPeriodQueueTracker {

    // TODO: Investigate reporting MediaPeriodId in renderer events.

    private final Period period;

    private ImmutableList<MediaPeriodId> mediaPeriodQueue;
    private ImmutableMap<MediaPeriodId, Timeline> mediaPeriodTimelines;
    @Nullable private MediaPeriodId currentPlayerMediaPeriod;
    private @MonotonicNonNull MediaPeriodId playingMediaPeriod;
    private @MonotonicNonNull MediaPeriodId readingMediaPeriod;

    public MediaPeriodQueueTracker(Period period) {
      this.period = period;
      mediaPeriodQueue = ImmutableList.of();
      mediaPeriodTimelines = ImmutableMap.of();
    }

    /**
     * Returns the {@link MediaPeriodId} of the media period corresponding the current position of
     * the player.
     *
     * <p>May be null if no matching media period has been created yet.
     */
    @Nullable
    public MediaPeriodId getCurrentPlayerMediaPeriod() {
      return currentPlayerMediaPeriod;
    }

    /**
     * Returns the {@link MediaPeriodId} of the media period at the front of the queue. If the queue
     * is empty, this is the last media period which was at the front of the queue.
     *
     * <p>May be null, if no media period has been created yet.
     */
    @Nullable
    public MediaPeriodId getPlayingMediaPeriod() {
      return playingMediaPeriod;
    }

    /**
     * Returns the {@link MediaPeriodId} of the media period currently being read by the player. If
     * the queue is empty, this is the last media period which was read by the player.
     *
     * <p>May be null, if no media period has been created yet.
     */
    @Nullable
    public MediaPeriodId getReadingMediaPeriod() {
      return readingMediaPeriod;
    }

    /**
     * Returns the {@link MediaPeriodId} of the media period at the end of the queue which is
     * currently loading or will be the next one loading.
     *
     * <p>May be null, if no media period is active yet.
     */
    @Nullable
    public MediaPeriodId getLoadingMediaPeriod() {
      return mediaPeriodQueue.isEmpty() ? null : Iterables.getLast(mediaPeriodQueue);
    }

    /**
     * Returns the most recent {@link Timeline} for the given {@link MediaPeriodId}, or null if no
     * timeline is available.
     */
    @Nullable
    public Timeline getMediaPeriodIdTimeline(MediaPeriodId mediaPeriodId) {
      return mediaPeriodTimelines.get(mediaPeriodId);
    }

    /** Updates the queue tracker with a reported position discontinuity. */
    public void onPositionDiscontinuity(Player player) {
      currentPlayerMediaPeriod =
          findCurrentPlayerMediaPeriodInQueue(player, mediaPeriodQueue, playingMediaPeriod, period);
    }

    /** Updates the queue tracker with a reported timeline change. */
    public void onTimelineChanged(Player player) {
      currentPlayerMediaPeriod =
          findCurrentPlayerMediaPeriodInQueue(player, mediaPeriodQueue, playingMediaPeriod, period);
      updateMediaPeriodTimelines(/* preferredTimeline= */ player.getCurrentTimeline());
    }

    /** Updates the queue tracker to a new queue of media periods. */
    public void onQueueUpdated(
        List<MediaPeriodId> queue, @Nullable MediaPeriodId readingPeriod, Player player) {
      mediaPeriodQueue = ImmutableList.copyOf(queue);
      if (!queue.isEmpty()) {
        playingMediaPeriod = queue.get(0);
        readingMediaPeriod = checkNotNull(readingPeriod);
      }
      if (currentPlayerMediaPeriod == null) {
        currentPlayerMediaPeriod =
            findCurrentPlayerMediaPeriodInQueue(
                player, mediaPeriodQueue, playingMediaPeriod, period);
      }
      updateMediaPeriodTimelines(/* preferredTimeline= */ player.getCurrentTimeline());
    }

    private void updateMediaPeriodTimelines(Timeline preferredTimeline) {
      ImmutableMap.Builder<MediaPeriodId, Timeline> builder = ImmutableMap.builder();
      if (mediaPeriodQueue.isEmpty()) {
        addTimelineForMediaPeriodId(builder, playingMediaPeriod, preferredTimeline);
        if (!Objects.equal(readingMediaPeriod, playingMediaPeriod)) {
          addTimelineForMediaPeriodId(builder, readingMediaPeriod, preferredTimeline);
        }
        if (!Objects.equal(currentPlayerMediaPeriod, playingMediaPeriod)
            && !Objects.equal(currentPlayerMediaPeriod, readingMediaPeriod)) {
          addTimelineForMediaPeriodId(builder, currentPlayerMediaPeriod, preferredTimeline);
        }
      } else {
        for (int i = 0; i < mediaPeriodQueue.size(); i++) {
          addTimelineForMediaPeriodId(builder, mediaPeriodQueue.get(i), preferredTimeline);
        }
        if (!mediaPeriodQueue.contains(currentPlayerMediaPeriod)) {
          addTimelineForMediaPeriodId(builder, currentPlayerMediaPeriod, preferredTimeline);
        }
      }
      mediaPeriodTimelines = builder.buildOrThrow();
    }

    private void addTimelineForMediaPeriodId(
        ImmutableMap.Builder<MediaPeriodId, Timeline> mediaPeriodTimelinesBuilder,
        @Nullable MediaPeriodId mediaPeriodId,
        Timeline preferredTimeline) {
      if (mediaPeriodId == null) {
        return;
      }
      if (preferredTimeline.getIndexOfPeriod(mediaPeriodId.periodUid) != C.INDEX_UNSET) {
        mediaPeriodTimelinesBuilder.put(mediaPeriodId, preferredTimeline);
      } else {
        @Nullable Timeline existingTimeline = mediaPeriodTimelines.get(mediaPeriodId);
        if (existingTimeline != null) {
          mediaPeriodTimelinesBuilder.put(mediaPeriodId, existingTimeline);
        }
      }
    }

    @Nullable
    private static MediaPeriodId findCurrentPlayerMediaPeriodInQueue(
        Player player,
        ImmutableList<MediaPeriodId> mediaPeriodQueue,
        @Nullable MediaPeriodId playingMediaPeriod,
        Period period) {
      Timeline playerTimeline = player.getCurrentTimeline();
      int playerPeriodIndex = player.getCurrentPeriodIndex();
      @Nullable
      Object playerPeriodUid =
          playerTimeline.isEmpty() ? null : playerTimeline.getUidOfPeriod(playerPeriodIndex);
      int playerNextAdGroupIndex =
          player.isPlayingAd() || playerTimeline.isEmpty()
              ? C.INDEX_UNSET
              : playerTimeline
                  .getPeriod(playerPeriodIndex, period)
                  .getAdGroupIndexAfterPositionUs(
                      Util.msToUs(player.getCurrentPosition()) - period.getPositionInWindowUs());
      for (int i = 0; i < mediaPeriodQueue.size(); i++) {
        MediaPeriodId mediaPeriodId = mediaPeriodQueue.get(i);
        if (isMatchingMediaPeriod(
            mediaPeriodId,
            playerPeriodUid,
            player.isPlayingAd(),
            player.getCurrentAdGroupIndex(),
            player.getCurrentAdIndexInAdGroup(),
            playerNextAdGroupIndex)) {
          return mediaPeriodId;
        }
      }
      if (mediaPeriodQueue.isEmpty() && playingMediaPeriod != null) {
        if (isMatchingMediaPeriod(
            playingMediaPeriod,
            playerPeriodUid,
            player.isPlayingAd(),
            player.getCurrentAdGroupIndex(),
            player.getCurrentAdIndexInAdGroup(),
            playerNextAdGroupIndex)) {
          return playingMediaPeriod;
        }
      }
      return null;
    }

    private static boolean isMatchingMediaPeriod(
        MediaPeriodId mediaPeriodId,
        @Nullable Object playerPeriodUid,
        boolean isPlayingAd,
        int playerAdGroupIndex,
        int playerAdIndexInAdGroup,
        int playerNextAdGroupIndex) {
      if (!mediaPeriodId.periodUid.equals(playerPeriodUid)) {
        return false;
      }
      // Timeline period matches. Still need to check ad information.
      return (isPlayingAd
              && mediaPeriodId.adGroupIndex == playerAdGroupIndex
              && mediaPeriodId.adIndexInAdGroup == playerAdIndexInAdGroup)
          || (!isPlayingAd
              && mediaPeriodId.adGroupIndex == C.INDEX_UNSET
              && mediaPeriodId.nextAdGroupIndex == playerNextAdGroupIndex);
    }
  }
}
