package com.aresstack.playwright4j.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Selects the video-encoding backend. JCodec is the default; VLC is used only when
 * {@code -Dplaywright4j.video.vlc.path} is set to a valid vlc executable. There is no automatic
 * discovery (no PATH/registry/Program Files scan) and no silent VLC fallback.
 */
public final class VideoBackends {

    public static final String VLC_PATH_PROPERTY = "playwright4j.video.vlc.path";

    private VideoBackends() {
    }

    public static VideoEncoderBackend select() {
        String vlcPath = System.getProperty(VLC_PATH_PROPERTY);
        if (vlcPath != null && !vlcPath.trim().isEmpty()) {
            if (isValidVlcPath(vlcPath)) {
                return new VlcVideoEncoderBackend(vlcPath);
            }
            System.err.println("[playwright4j-video] " + VLC_PATH_PROPERTY + "=" + vlcPath
                    + " is not a valid vlc executable; falling back to the JCodec backend.");
        }
        return new JcodecVideoEncoderBackend();
    }

    /** A valid VLC path is an existing, regular, executable file (the vlc binary itself). */
    public static boolean isValidVlcPath(String vlcPath) {
        try {
            Path path = Paths.get(vlcPath);
            return Files.isRegularFile(path) && Files.isExecutable(path);
        } catch (Exception exception) {
            return false;
        }
    }
}
