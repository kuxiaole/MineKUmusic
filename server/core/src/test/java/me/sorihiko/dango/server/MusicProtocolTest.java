package me.sorihiko.dango.server;

import com.google.gson.JsonObject;
import me.sorihiko.dango.protocol.MusicProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MusicProtocolTest {
    @Test void completeLyricsAndMetadataRoundTripAcrossFrames() throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("lrc", "[00:01.00] Synthetic lyric line\n".repeat(3000));
        body.addProperty("name", "Synthetic test song");
        JsonObject message = MusicProtocol.message("audio", "fixture", body);
        var frames = MusicProtocol.frames(message); assertTrue(frames.size() > 1);
        MusicProtocol.Assembler assembler = new MusicProtocol.Assembler(); JsonObject result = null;
        for (byte[] frame : frames) { assertTrue(frame.length <= MusicProtocol.MAX_FRAME); result = assembler.accept(frame); }
        assertEquals(message, result);
    }
    @Test void separateTransfersCanBeInterleaved() throws Exception {
        JsonObject first = new JsonObject(); first.addProperty("text", "a".repeat(30000));
        JsonObject second = new JsonObject(); second.addProperty("text", "b".repeat(30000));
        var a = MusicProtocol.frames(first); var b = MusicProtocol.frames(second);
        MusicProtocol.Assembler assembler = new MusicProtocol.Assembler();
        assertNull(assembler.accept(a.getFirst())); assertNull(assembler.accept(b.getFirst()));
        assertEquals(first, assembler.accept(a.getLast())); assertEquals(second, assembler.accept(b.getLast()));
    }
}
