package com.lib.flutter_pcm_sound;

import android.os.Build;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

import android.content.Context;
import android.media.AudioFocusRequest;

// Add these fields


/**
 * FlutterPcmSoundPlugin implements a "one pedal" PCM sound playback mechanism.
 * Playback starts automatically when samples are fed and stops when no more samples are available.
 */
public class FlutterPcmSoundPlugin implements
    FlutterPlugin,
    MethodChannel.MethodCallHandler
{
    private static final String CHANNEL_NAME = "flutter_pcm_sound/methods";
    private static final int MAX_FRAMES_PER_BUFFER = 200;

    private MethodChannel mMethodChannel;
    private Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private Thread playbackThread;
    private volatile boolean mShouldCleanup = false;

    private AudioTrack mAudioTrack;
    private int mNumChannels;
    private int mMinBufferSize;
    private boolean mDidSetup = false;

    private AudioManager mAudioManager;
private AudioFocusRequest mFocusRequest;

    private long mFeedThreshold = 8000;
    private long mTotalFeeds = 0;
    private long mLastLowBufferFeed = 0;
    private long mLastZeroFeed = 0;




    // Thread-safe queue for storing audio samples
    private final LinkedBlockingQueue<ByteBuffer> mSamples = new LinkedBlockingQueue<>();

    // Log level enum (kept for potential future use)
    private enum LogLevel {
        NONE,
        ERROR,
        STANDARD,
        VERBOSE
    }

    private LogLevel mLogLevel = LogLevel.VERBOSE;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
          BinaryMessenger messenger = binding.getBinaryMessenger();
    mMethodChannel = new MethodChannel(messenger, CHANNEL_NAME);
    mMethodChannel.setMethodCallHandler(this);
    mAudioManager = (AudioManager) binding.getApplicationContext()
        .getSystemService(Context.AUDIO_SERVICE);

    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        mMethodChannel.setMethodCallHandler(null);
        cleanup();
    }

    @Override
    @SuppressWarnings("deprecation") // Needed for compatibility with Android < 23
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        try {
            switch (call.method) {
                case "setLogLevel": {
                    result.success(true);
                    break;
                }
                case "setup": {
                    int sampleRate = call.argument("sample_rate");
                    mNumChannels = call.argument("num_channels");
                    String androidAudioUsage = call.argument("android_audio_usage");
                    String androidAudioContentType = call.argument("android_audio_content_type");
                    String androidLegacyStreamType = call.argument("android_legacy_stream_type");

                    int audioUsage = resolveAudioUsage(androidAudioUsage);
                    int contentType = resolveAudioContentType(androidAudioContentType);
                    int legacyStreamType = resolveLegacyStreamType(androidLegacyStreamType);

                    // Cleanup existing resources if any
                    if (mAudioTrack != null) {
                        cleanup();
                    }

                    int channelConfig = (mNumChannels == 2) ?
                        AudioFormat.CHANNEL_OUT_STEREO :
                        AudioFormat.CHANNEL_OUT_MONO;

                    mMinBufferSize = AudioTrack.getMinBufferSize(
                        sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

                    if (mMinBufferSize == AudioTrack.ERROR || mMinBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                        result.error("AudioTrackError", "Invalid buffer size.", null);
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= 23) { // Android 6 (Marshmallow) and above
                        mAudioTrack = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(audioUsage)
                                    .setContentType(contentType)
                                    .build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelConfig)
                                    .build())
                            .setBufferSizeInBytes(mMinBufferSize)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    } else {
                        mAudioTrack = new AudioTrack(
                            legacyStreamType,
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            mMinBufferSize,
                            AudioTrack.MODE_STREAM);
                    }

                    if (mAudioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                        result.error("AudioTrackError", "AudioTrack initialization failed.", null);
                        mAudioTrack.release();
                        mAudioTrack = null;
                        return;
                    }

                    // reset
                    mSamples.clear();
                    mShouldCleanup = false;

                    // start playback thread
                    playbackThread = new Thread(this::playbackThreadLoop, "PCMPlaybackThread");
                    playbackThread.setPriority(Thread.MAX_PRIORITY);
                    playbackThread.start();

                    mDidSetup = true;

                    result.success(true);
                    break;
                }
                case "feed": {

                    // check setup (to match iOS behavior)
                    if (mDidSetup == false) {
                        result.error("Setup", "must call setup first", null);
                        return;
                    }

                    byte[] buffer = call.argument("buffer");

                    // Split for better performance
                    synchronized (mSamples) {
                        int max = 0;

for (int i = 0; i + 1 < buffer.length; i += 2) {
    short sample = (short) (
        (buffer[i] & 0xff) |
        (buffer[i + 1] << 8)
    );

    max = Math.max(max, Math.abs(sample));
}

android.util.Log.d(
    "PCM",
    "Feed: bytes=" + buffer.length +
    " maxAmplitude=" + max
);
    mSamples.add(ByteBuffer.wrap(buffer));
    mTotalFeeds += 1;
}

                    result.success(true);
                    break;
                }
                case "setFeedThreshold": {
                    long feedThreshold = ((Number) call.argument("feed_threshold")).longValue();

                    synchronized (mSamples) {
                        mFeedThreshold = feedThreshold;
                    }

                    result.success(true);
                    break;
                }
                case "release": {
                    cleanup();
                    result.success(true);
                    break;
                }
                default:
                    result.notImplemented();
                    break;
            }


        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stackTrace = sw.toString();
            result.error("androidException", e.toString(), stackTrace);
            return;
        }
    }

    /**
     * Cleans up resources by stopping the playback thread and releasing AudioTrack.
     */
    private void cleanup() {
        // stop playback thread
        if (playbackThread != null) {
            mShouldCleanup = true;
            playbackThread.interrupt();
            try {
                playbackThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            playbackThread = null;
            mDidSetup = false;
        }
    }

    /**
     * Invokes the 'OnFeedSamples' callback with the number of remaining frames.
     */
    private void invokeFeedCallback(long remainingFrames) {
        Map<String, Object> response = new HashMap<>();
        response.put("remaining_frames", remainingFrames);
        mMethodChannel.invokeMethod("OnFeedSamples", response);
    }

    /**
     * The main loop of the playback thread.
     */
    private void playbackThreadLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

        mAudioTrack.play();

android.util.Log.d(
    "PCM",
    "AudioTrack state=" + mAudioTrack.getState()
        + " playState=" + mAudioTrack.getPlayState()
        + " bufferSize=" + mMinBufferSize
);

        while (!mShouldCleanup) {
            ByteBuffer data = null;
            try {
                // blocks indefinitely until new data
                data = mSamples.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                continue;
            }

            // write
            int requested = data.remaining();

int written = mAudioTrack.write(
    data,
    requested,
    AudioTrack.WRITE_BLOCKING
);

android.util.Log.d(
    "PCM",
    "Requested=" + requested +
    " Written=" + written
);

if (written < 0) {
    android.util.Log.e(
        "PCM",
        "AudioTrack.write() failed with error " + written
    );
}

            long remainingFrames;
            long totalFeeds;
            long feedThreshold;

            // grab shared data
            synchronized (mSamples) {
                long totalBytes = 0;
                for (ByteBuffer sampleBuffer : mSamples) {
                    totalBytes += sampleBuffer.remaining();
                }
                remainingFrames = totalBytes / (2 * mNumChannels);
                totalFeeds = mTotalFeeds;
                feedThreshold = mFeedThreshold;
            }

            // check for events
            boolean isLowBufferEvent = (remainingFrames <= feedThreshold) && (mLastLowBufferFeed != totalFeeds);
            boolean isZeroCrossingEvent = (remainingFrames == 0) && (mLastZeroFeed != totalFeeds);

            // send events
            if (isLowBufferEvent || isZeroCrossingEvent) {
                if (isLowBufferEvent) {mLastLowBufferFeed = totalFeeds;}
                if (isZeroCrossingEvent) {mLastZeroFeed = totalFeeds;}
                mainThreadHandler.post(() -> invokeFeedCallback(remainingFrames));
            }
        }

        mAudioTrack.stop();
        mAudioTrack.flush();
        mAudioTrack.release();
        mAudioTrack = null;
    }


    private List<ByteBuffer> split(byte[] buffer, int maxSize) {
        List<ByteBuffer> chunks = new ArrayList<>();
        int offset = 0;
        while (offset < buffer.length) {
            int length = Math.min(buffer.length - offset, maxSize);
            ByteBuffer b = ByteBuffer.wrap(buffer, offset, length);
            chunks.add(b);
            offset += length;
        }
        return chunks;
    }

    private int resolveAudioUsage(String value) {
        if (value == null) return AudioAttributes.USAGE_MEDIA;
        switch (value) {
            case "unknown":                      return AudioAttributes.USAGE_UNKNOWN;
            case "voiceCommunication":           return AudioAttributes.USAGE_VOICE_COMMUNICATION;
            case "voiceCommunicationSignalling": return AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING;
            case "alarm":                        return AudioAttributes.USAGE_ALARM;
            case "notification":                 return AudioAttributes.USAGE_NOTIFICATION;
            case "notificationRingtone":         return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
            case "notificationEvent":            return AudioAttributes.USAGE_NOTIFICATION_EVENT;
            case "assistanceAccessibility":      return AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY;
            case "assistanceNavigationGuidance": return AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
            case "assistanceSonification":       return AudioAttributes.USAGE_ASSISTANCE_SONIFICATION;
            case "game":                         return AudioAttributes.USAGE_GAME;
            case "assistant":                    return AudioAttributes.USAGE_ASSISTANT;
            case "media":
            default:                             return AudioAttributes.USAGE_MEDIA;
        }
    }

    private int resolveAudioContentType(String value) {
        if (value == null) return AudioAttributes.CONTENT_TYPE_MUSIC;
        switch (value) {
            case "unknown":      return AudioAttributes.CONTENT_TYPE_UNKNOWN;
            case "speech":       return AudioAttributes.CONTENT_TYPE_SPEECH;
            case "movie":        return AudioAttributes.CONTENT_TYPE_MOVIE;
            case "sonification": return AudioAttributes.CONTENT_TYPE_SONIFICATION;
            case "music":
            default:             return AudioAttributes.CONTENT_TYPE_MUSIC;
        }
    }

    @SuppressWarnings("deprecation")
    private int resolveLegacyStreamType(String value) {
        if (value == null) return AudioManager.STREAM_MUSIC;
        switch (value) {
            case "voiceCall":    return AudioManager.STREAM_VOICE_CALL;
            case "system":       return AudioManager.STREAM_SYSTEM;
            case "ring":         return AudioManager.STREAM_RING;
            case "alarm":        return AudioManager.STREAM_ALARM;
            case "notification": return AudioManager.STREAM_NOTIFICATION;
            case "dtmf":         return AudioManager.STREAM_DTMF;
            case "accessibility":
                if (Build.VERSION.SDK_INT >= 26) return AudioManager.STREAM_ACCESSIBILITY;
                return AudioManager.STREAM_MUSIC;
            case "music":
            default:             return AudioManager.STREAM_MUSIC;
        }
    }
}
