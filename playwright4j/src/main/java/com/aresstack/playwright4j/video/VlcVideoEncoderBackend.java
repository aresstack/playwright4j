package com.aresstack.playwright4j.video;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in video backend that delegates encoding to a VLC executable. Enabled only when
 * {@code -Dplaywright4j.video.vlc.path} points at a valid {@code vlc} executable (the property is
 * the path to the binary itself, e.g. {@code .../VLC/vlc.exe}).
 *
 * <p>VLC reads the MJPEG byte stream from stdin and transcodes it to VP8/WebM at the output path.
 * This backend is not exercised by the default test run and is provided for hardened environments
 * that prefer VLC over the bundled native ffmpeg; the exact VLC CLI may need tuning per version.
 */
public final class VlcVideoEncoderBackend implements VideoEncoderBackend {

    private final String vlcExecutable;

    public VlcVideoEncoderBackend(String vlcExecutable) {
        this.vlcExecutable = vlcExecutable;
    }

    @Override
    public VideoEncoderHandle start(VideoEncoderRequest request, Runnable onExit) {
        List<String> command = new ArrayList<String>();
        command.add(vlcExecutable);
        command.add("-I");
        command.add("dummy");
        command.add("--quiet");
        command.add("--no-audio");
        command.add("--demux=mjpeg");
        command.add("--mjpeg-fps=" + request.fps());
        command.add("-");
        command.add("--sout");
        command.add("#transcode{vcodec=VP80,fps=" + request.fps() + "}:standard{access=file,mux=webm,dst="
                + request.outputPath() + "}");
        command.add("vlc://quit");

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
        } catch (Exception exception) {
            System.err.println("[playwright4j-video] VLC start failed (" + exception
                    + "); cannot encode video via VLC.");
            onExit.run();
            return new NoopHandle();
        }
        return new VlcEncoderHandle(process, onExit);
    }

    private static final class VlcEncoderHandle implements VideoEncoderHandle {

        private final Process process;
        private final OutputStream stdin;

        private VlcEncoderHandle(Process process, final Runnable onExit) {
            this.process = process;
            this.stdin = process.getOutputStream();
            Thread watcher = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        process.waitFor();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    } finally {
                        onExit.run();
                    }
                }
            }, "playwright4j-vlc-exit");
            watcher.setDaemon(true);
            watcher.start();
        }

        @Override
        public void write(byte[] data) {
            try {
                stdin.write(data);
                stdin.flush();
            } catch (Exception ignored) {
                // VLC ended; exit watcher will fire.
            }
        }

        @Override
        public void finish() {
            try {
                stdin.close();
            } catch (Exception ignored) {
                // Already closed.
            }
        }

        @Override
        public void kill() {
            process.destroyForcibly();
        }
    }

    private static final class NoopHandle implements VideoEncoderHandle {
        @Override
        public void write(byte[] data) {
        }

        @Override
        public void finish() {
        }

        @Override
        public void kill() {
        }
    }
}
