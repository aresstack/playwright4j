package com.aresstack.playwright4j.video;

/**
 * A pluggable video-encoding backend. The default is {@link JcodecVideoEncoderBackend}; VLC is used
 * only when {@code -Dplaywright4j.video.vlc.path} points at a valid vlc executable.
 */
public interface VideoEncoderBackend {

    /**
     * Starts encoding. {@code onExit} is invoked exactly once when the output is finalized or the
     * encoder is killed, so the caller can emit the synthetic process "exit" event.
     */
    VideoEncoderHandle start(VideoEncoderRequest request, Runnable onExit);
}
