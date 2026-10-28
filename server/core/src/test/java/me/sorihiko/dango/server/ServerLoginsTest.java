package me.sorihiko.dango.server;

import com.google.gson.JsonObject;
import me.sorihiko.dango.music.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Lifecycle fixtures only; QR content and credentials are synthetic. */
class ServerLoginsTest {
    @TempDir Path directory;
    private NativeAccount account() {
        var jar = new CookieManager();
        for (String[] pair : List.of(new String[]{"qqmusic_uin", "123"}, new String[]{"qqmusic_key", "synthetic-test-session"})) {
            var cookie = new HttpCookie(pair[0], pair[1]); cookie.setDomain(".qq.com"); cookie.setPath("/");
            jar.getCookieStore().add(URI.create("https://y.qq.com/"), cookie);
        }
        return new NativeAccount(MusicSource.QQ, "123", "Fixture", "", "", jar);
    }
    @Test void verifiedAccountIsSavedAsArrayAndOnlyStatusReachesItsInitiator() throws Exception {
        var owner = Executors.newSingleThreadExecutor();
        var events = new CopyOnWriteArrayList<JsonObject>(); var done = new CountDownLatch(1); var player = UUID.randomUUID();
        try (var logins = new ServerLogins(directory, owner::execute, (id, body) -> {
            assertEquals(player, id); events.add(body);
            if (body.get("state").getAsString().equals("success")) done.countDown();
        }, source -> new ProviderQrLogin() {
            @Override public NativeAccount run(Observer observer) { observer.image(new byte[]{1, 2, 3}); observer.state("verifying"); return account(); }
            @Override public void close() { }
        })) {
            logins.start(player, "qq"); assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(Files.readString(directory.resolve("qq-cookie.json")).startsWith("["));
            assertFalse(events.toString().contains("synthetic-test-session"));
            assertFalse(Files.exists(directory.resolve("netease-cookie.json")));
        } finally { owner.shutdown(); assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS)); }
    }
    @Test void closingUnconfirmedDialogRetainsExistingAccount() throws Exception {
        var owner = Executors.newSingleThreadExecutor(); var opened = new CountDownLatch(1); var closed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1); var player = UUID.randomUUID();
        Files.writeString(directory.resolve("qq-cookie.json"), "[]");
        try (var logins = new ServerLogins(directory, owner::execute, (id, body) -> {
            if (body.get("state").getAsString().equals("cancelled")) cancelled.countDown();
        }, source -> new ProviderQrLogin() {
            @Override public NativeAccount run(Observer observer) {
                opened.countDown();
                try { closed.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return account();
            }
            @Override public void close() { closed.countDown(); }
        })) {
            logins.start(player, "qq"); assertTrue(opened.await(5, TimeUnit.SECONDS));
            logins.cancel(player, "qq", ""); assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertEquals("[]", Files.readString(directory.resolve("qq-cookie.json")));
        } finally { owner.shutdown(); assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
