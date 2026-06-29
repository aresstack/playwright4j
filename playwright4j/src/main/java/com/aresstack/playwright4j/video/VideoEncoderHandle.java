package com.aresstack.playwright4j.video;

/**
 * A running video encoder that behaves like the ffmpeg process Playwright expects: it consumes
 * MJPEG bytes on its "stdin", finalizes the output file when input ends, and can be killed.
 */
public interface VideoEncoderHandle {

    /** Feeds raw MJPEG bytes (as written to ffmpeg's stdin). Must not block the caller. */
    void write(byte[] data);

    /** Signals end of input; the encoder finalizes the output file and then reports completion. */
    void finish();

    /** Aborts encoding and releases resources. */
    void kill();
}
