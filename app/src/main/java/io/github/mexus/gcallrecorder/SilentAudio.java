package io.github.mexus.gcallrecorder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

final class SilentAudio {
    private SilentAudio() {}

    static final byte[] WAV = {
            82, 73, 70, 70, 36, 0, 0, 0, 87, 65, 86,
            69, 102, 109, 116, 32, 16, 0, 0, 0, 1, 0,
            1, 0, -128, 62, 0, 0, 0, 125, 0, 0, 2,
            0, 16, 0, 100, 97, 116, 97, 0, 0, 0, 0};

    static void write(File f) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(WAV);
        }
    }
}
