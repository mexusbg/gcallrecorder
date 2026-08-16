package io.github.mexus.gcallrecorder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.junit.Test;

public class SilentAudioTest {
    @Test public void wavIsValidRiffHeaderWithNoSamples() {
        byte[] w = SilentAudio.WAV;
        assertEquals("length is a bare 44-byte header", 44, w.length);
        assertArrayEquals("RIFF magic", new byte[]{'R','I','F','F'}, new byte[]{w[0],w[1],w[2],w[3]});
        assertArrayEquals("WAVE magic", new byte[]{'W','A','V','E'}, new byte[]{w[8],w[9],w[10],w[11]});
        assertArrayEquals("fmt  chunk", new byte[]{'f','m','t',' '}, new byte[]{w[12],w[13],w[14],w[15]});
        assertArrayEquals("data chunk", new byte[]{'d','a','t','a'}, new byte[]{w[36],w[37],w[38],w[39]});
        assertEquals("data size 0", 0, w[40] | w[41] | w[42] | w[43]);
    }

    @Test public void writeProducesFileWithExactWavBytes() throws IOException {
        File f = File.createTempFile("silent-audio-test", ".wav");
        f.deleteOnExit();
        try {
            SilentAudio.write(f);
            byte[] readBack = new byte[SilentAudio.WAV.length];
            try (FileInputStream in = new FileInputStream(f)) {
                int total = 0;
                while (total < readBack.length) {
                    int n = in.read(readBack, total, readBack.length - total);
                    if (n < 0) break;
                    total += n;
                }
                assertEquals("wrote exactly the WAV-length bytes", SilentAudio.WAV.length, total);
                assertEquals("no trailing bytes", -1, in.read());
            }
            assertArrayEquals("written bytes equal SilentAudio.WAV", SilentAudio.WAV, readBack);
        } finally {
            f.delete();
        }
    }
}
