package com.aresstack.playwright4j.video;

import java.util.List;

/**
 * Describes a video-encoding request derived from the ffmpeg command line that Playwright Core
 * spawns. The original Playwright JS is unchanged; our child_process.spawn shim routes the ffmpeg
 * spawn here so a Java backend (JCodec by default) can produce the output file.
 */
public final class VideoEncoderRequest {

    private final String outputPath;
    private final int width;
    private final int height;
    private final int fps;
    private final List<String> ffmpegArguments;

    public VideoEncoderRequest(String outputPath, int width, int height, int fps, List<String> ffmpegArguments) {
        this.outputPath = outputPath;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.ffmpegArguments = ffmpegArguments;
    }

    public String outputPath() {
        return outputPath;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int fps() {
        return fps;
    }

    public List<String> ffmpegArguments() {
        return ffmpegArguments;
    }

    /**
     * Parses the ffmpeg argument list. The output file is the last positional argument; frame rate
     * comes from {@code -r}; target size from a {@code -vf} crop/pad filter (falling back to a
     * default). Dimensions are forced even (required by most codecs).
     */
    public static VideoEncoderRequest fromFfmpegArguments(List<String> arguments) {
        // ffmpeg's output file is always the final argument.
        String outputPath = arguments.isEmpty() ? null : arguments.get(arguments.size() - 1);

        int fps = 25;
        int width = 1280;
        int height = 720;
        for (int index = 0; index < arguments.size(); index++) {
            String argument = arguments.get(index);
            if (argument.equals("-r") && index + 1 < arguments.size()) {
                fps = parseIntOr(arguments.get(index + 1), fps);
            }
            if (argument.equals("-vf") && index + 1 < arguments.size()) {
                int[] size = parseFilterSize(arguments.get(index + 1));
                if (size != null) {
                    width = size[0];
                    height = size[1];
                }
            }
        }
        width = Math.max(2, width - (width % 2));
        height = Math.max(2, height - (height % 2));
        return new VideoEncoderRequest(outputPath, width, height, fps, arguments);
    }

    private static int[] parseFilterSize(String filter) {
        // Prefer crop=W:H (final size); otherwise pad=W:H.
        int[] crop = matchSize(filter, "crop=");
        if (crop != null) {
            return crop;
        }
        return matchSize(filter, "pad=");
    }

    private static int[] matchSize(String filter, String prefix) {
        int start = filter.indexOf(prefix);
        if (start < 0) {
            return null;
        }
        String rest = filter.substring(start + prefix.length());
        String[] parts = rest.split("[:,]");
        if (parts.length >= 2) {
            int w = parseIntOr(parts[0], -1);
            int h = parseIntOr(parts[1], -1);
            if (w > 0 && h > 0) {
                return new int[] { w, h };
            }
        }
        return null;
    }

    private static int parseIntOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }
}
