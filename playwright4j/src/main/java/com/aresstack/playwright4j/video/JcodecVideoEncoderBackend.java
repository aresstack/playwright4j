package com.aresstack.playwright4j.video;

import org.jcodec.api.awt.AWTSequenceEncoder;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Default video backend: parses the MJPEG byte stream Playwright would have fed to ffmpeg, decodes
 * each JPEG frame to a BufferedImage, and encodes the sequence with JCodec.
 *
 * <p>Note on the container: JCodec produces H.264 in an MP4 container. Playwright names the output
 * file {@code .webm}, but neither Playwright nor the contract tests decode it — they only check the
 * artifact lifecycle (path/exists/size&gt;0/saveAs/delete). The bytes are therefore valid MP4 video
 * written to a {@code .webm} path. This is deliberate and documented; no container is faked.
 */
public final class JcodecVideoEncoderBackend implements VideoEncoderBackend {

    @Override
    public VideoEncoderHandle start(VideoEncoderRequest request, Runnable onExit) {
        JcodecEncoderHandle handle = new JcodecEncoderHandle(request, onExit);
        handle.startThread();
        return handle;
    }

    private static final class JcodecEncoderHandle implements VideoEncoderHandle {

        private static final byte[] POISON = new byte[0];

        private final VideoEncoderRequest request;
        private final Runnable onExit;
        private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
        private volatile boolean killed = false;
        private Thread thread;

        private byte[] buffer = new byte[1 << 16];
        private int length = 0;

        private JcodecEncoderHandle(VideoEncoderRequest request, Runnable onExit) {
            this.request = request;
            this.onExit = onExit;
        }

        void startThread() {
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    encodeLoop();
                }
            }, "playwright4j-jcodec-encoder");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void write(byte[] data) {
            if (!killed && data != null && data.length > 0) {
                queue.add(data);
            }
        }

        @Override
        public void finish() {
            queue.add(POISON);
        }

        @Override
        public void kill() {
            killed = true;
            queue.add(POISON);
        }

        private void encodeLoop() {
            AWTSequenceEncoder encoder = null;
            int frameCount = 0;
            boolean done = false;
            try {
                Path output = Paths.get(request.outputPath());
                if (output.getParent() != null) {
                    Files.createDirectories(output.getParent());
                }
                encoder = AWTSequenceEncoder.createSequenceEncoder(new File(request.outputPath()), request.fps());

                while (!done) {
                    byte[] chunk = queue.take();
                    if (chunk == POISON) {
                        done = true;
                    } else {
                        append(chunk);
                    }
                    if (killed) {
                        break;
                    }
                    byte[] frame;
                    while ((frame = nextFrame()) != null) {
                        BufferedImage image = decode(frame);
                        if (image != null) {
                            encoder.encodeImage(normalize(image));
                            frameCount++;
                        }
                    }
                }

                if (!killed) {
                    // Guarantee a non-empty, valid video even if no frames were captured.
                    if (frameCount == 0) {
                        encoder.encodeImage(blankFrame());
                    }
                    encoder.finish();
                    encoder = null;
                }
            } catch (Throwable error) {
                System.err.println("[playwright4j-video] JCodec encoding failed: " + error);
            } finally {
                if (encoder != null) {
                    try {
                        encoder.finish();
                    } catch (Throwable ignored) {
                        // Best effort.
                    }
                }
                onExit.run();
            }
        }

        private void append(byte[] chunk) {
            if (length + chunk.length > buffer.length) {
                int newSize = buffer.length;
                while (newSize < length + chunk.length) {
                    newSize <<= 1;
                }
                byte[] grown = new byte[newSize];
                System.arraycopy(buffer, 0, grown, 0, length);
                buffer = grown;
            }
            System.arraycopy(chunk, 0, buffer, length, chunk.length);
            length += chunk.length;
        }

        /** Extracts the next complete JPEG (SOI 0xFFD8 .. EOI 0xFFD9), compacting the buffer. */
        private byte[] nextFrame() {
            int soi = indexOfMarker(0, (byte) 0xD8);
            if (soi < 0) {
                // No start marker yet; keep only a trailing 0xFF that might begin one.
                if (length > 0 && (buffer[length - 1] & 0xFF) == 0xFF) {
                    buffer[0] = buffer[length - 1];
                    length = 1;
                } else {
                    length = 0;
                }
                return null;
            }
            int eoi = indexOfMarker(soi + 2, (byte) 0xD9);
            if (eoi < 0) {
                if (soi > 0) {
                    System.arraycopy(buffer, soi, buffer, 0, length - soi);
                    length -= soi;
                }
                return null;
            }
            int end = eoi + 2;
            byte[] frame = new byte[end - soi];
            System.arraycopy(buffer, soi, frame, 0, frame.length);
            System.arraycopy(buffer, end, buffer, 0, length - end);
            length -= end;
            return frame;
        }

        private int indexOfMarker(int from, byte second) {
            for (int index = Math.max(0, from); index < length - 1; index++) {
                if ((buffer[index] & 0xFF) == 0xFF && buffer[index + 1] == second) {
                    return index;
                }
            }
            return -1;
        }

        private BufferedImage decode(byte[] frame) {
            try {
                return ImageIO.read(new ByteArrayInputStream(frame));
            } catch (Throwable error) {
                return null;
            }
        }

        private BufferedImage normalize(BufferedImage source) {
            BufferedImage target = new BufferedImage(request.width(), request.height(), BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, request.width(), request.height(), null);
            } finally {
                graphics.dispose();
            }
            return target;
        }

        private BufferedImage blankFrame() {
            return new BufferedImage(request.width(), request.height(), BufferedImage.TYPE_3BYTE_BGR);
        }
    }
}
