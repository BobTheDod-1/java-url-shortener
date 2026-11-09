package shortener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class Tests {
    @FunctionalInterface interface Checked { void run() throws Exception; }
    private static int passed;
    private static final String URL = "https://example.org/path?q=1#part";
    private Tests() { }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public synchronized Instant instant() { return now; }
        synchronized void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
    private static Config config() { return new Config("https://short.local/", Duration.ofHours(24), 3, 1000, 10); }
    private static LinkService service(Clock clock) { return new LinkService(config(), clock, uri -> { }); }
    private static void test(String name, Checked action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + passed + ": " + name);
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    private static void eq(Object actual, Object expected) { check(actual.equals(expected)); }
    private static void rejects(Class<? extends Exception> type, Checked action) throws Exception {
        try { action.run(); } catch (Exception ex) { if (type.isInstance(ex)) return; throw ex; }
        throw new AssertionError("Expected " + type.getSimpleName());
    }
    public static void main(String[] args) throws Exception {
        test("first creation generates UUID", () -> { try (var s = service(new MutableClock())) { check(s.create(null, URL, null).user() != null); } });
        test("different users get unique codes for same URL", () -> { try (var s = service(new MutableClock())) { check(!s.create(null, URL, null).shortUrl().equals(s.create(null, URL, null).shortUrl())); } });
        test("one user owns multiple links", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); s.create(a.user(), URL, null); eq(s.list(a.user()).size(), 2); } });
        test("lists are isolated by owner", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); s.create(null, URL, null); eq(s.list(a.user()).size(), 1); } });
        test("browser receives original URI", () -> {
            List<URI> opened = new ArrayList<>();
            try (var s = new LinkService(config(), new MutableClock(), opened::add)) {
                var a = s.create(null, URL, null); eq(s.open(a.shortUrl()), URI.create(URL)); eq(opened, List.of(URI.create(URL)));
            }
        });
        test("exactly limit visits allowed, next blocked", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, 2L); s.open(a.shortUrl()); s.open(a.shortUrl()); rejects(IllegalStateException.class, () -> s.open(a.shortUrl())); eq(s.list(a.user()).get(0).clicks(), 2L); } });
        test("limit exhaustion notifies owner once", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, 1L); s.open(a.shortUrl()); var notes = s.drainNotifications(a.user()); eq(notes.size(), 1); check(notes.get(0).contains("Лимит")); eq(s.drainNotifications(a.user()).size(), 0); } });
        test("failed browser opening does not consume visit", () -> { try (var s = new LinkService(config(), new MutableClock(), uri -> { throw new IOException("fake failure"); })) { var a = s.create(null, URL, 1L); rejects(IOException.class, () -> s.open(a.shortUrl())); eq(s.list(a.user()).get(0).clicks(), 0L); } });
        test("TTL boundary is exclusive", () -> { var clock = new MutableClock(); try (var s = service(clock)) { var a = s.create(null, URL, null); clock.advance(Duration.ofHours(24)); rejects(IllegalArgumentException.class, () -> s.open(a.shortUrl())); eq(s.list(a.user()).size(), 0); check(s.drainNotifications(a.user()).get(0).contains("Срок")); } });
        test("link works immediately before expiry", () -> { var clock = new MutableClock(); try (var s = service(clock)) { var a = s.create(null, URL, null); clock.advance(Duration.ofHours(24).minusMillis(1)); s.open(a.shortUrl()); } });
        test("background cleanup fires without console requests", () -> {
            var clock = new MutableClock();
            try (var s = service(clock)) {
                var a = s.create(null, URL, null);
                clock.advance(Duration.ofHours(25));
                // Reflection observes the store without calling a method that itself cleans it.
                var field = LinkService.class.getDeclaredField("links"); field.setAccessible(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                boolean empty = false;
                while (System.nanoTime() < deadline) {
                    synchronized (s) { empty = ((java.util.Map<?, ?>) field.get(s)).isEmpty(); }
                    if (empty) break;
                    Thread.sleep(10);
                }
                check(empty); eq(s.drainNotifications(a.user()).size(), 1);
            }
        });
        test("foreign owner cannot edit limit", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); rejects(SecurityException.class, () -> s.setLimit(UUID.randomUUID(), a.shortUrl(), 8)); } });
        test("foreign owner cannot change target", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); rejects(SecurityException.class, () -> s.setTarget(UUID.randomUUID(), a.shortUrl(), URL)); } });
        test("foreign owner cannot delete", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); rejects(SecurityException.class, () -> s.delete(UUID.randomUUID(), a.shortUrl())); } });
        test("owner can change target", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); s.setTarget(a.user(), a.shortUrl(), "https://example.net/"); eq(s.open(a.shortUrl()), URI.create("https://example.net/")); } });
        test("owner can delete", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, null); s.delete(a.user(), a.shortUrl()); rejects(IllegalArgumentException.class, () -> s.open(a.shortUrl())); } });
        test("raising limit re-enables exhausted link", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, 1L); s.open(a.shortUrl()); s.setLimit(a.user(), a.shortUrl(), 2); s.open(a.shortUrl()); eq(s.list(a.user()).get(0).clicks(), 2L); } });
        test("limit cannot be below actual visits", () -> { try (var s = service(new MutableClock())) { var a = s.create(null, URL, 3L); s.open(a.shortUrl()); s.open(a.shortUrl()); rejects(IllegalArgumentException.class, () -> s.setLimit(a.user(), a.shortUrl(), 1)); } });
        test("zero, negative and excessive limits rejected", () -> { try (var s = service(new MutableClock())) { for (long n : new long[]{0, -1, 1001}) rejects(IllegalArgumentException.class, () -> s.create(null, URL, n)); } });
        test("unsafe and malformed target URLs rejected", () -> { for (String url : List.of("javascript:alert(1)", "file:///etc/passwd", "example.org", "https://", "http://a b", "https://u:p@example.org", "https://example.org:99999")) rejects(IllegalArgumentException.class, () -> LinkService.validateUrl(url)); });
        test("foreign short URL prefix rejected", () -> { try (var s = service(new MutableClock())) { rejects(IllegalArgumentException.class, () -> s.open("https://other.org/abcdef")); } });
        test("unknown link rejected", () -> { try (var s = service(new MutableClock())) { rejects(IllegalArgumentException.class, () -> s.open("https://short.local/abcdef")); } });
        test("collision retried without overwriting another user's link", () -> {
            AtomicInteger n = new AtomicInteger();
            try (var s = new LinkService(config(), new MutableClock(), uri -> { }, () -> n.getAndIncrement() < 2 ? "abcdef" : "ghijkl")) {
                var a = s.create(null, URL, null); var b = s.create(null, URL, null);
                check(!a.shortUrl().equals(b.shortUrl())); eq(s.list(a.user()).size(), 1);
            }
        });
        test("codes of deleted links never reassigned", () -> {
            try (var s = new LinkService(config(), new MutableClock(), uri -> { }, () -> "abcdef")) {
                var a = s.create(null, URL, null); s.delete(a.user(), a.shortUrl());
                rejects(IllegalStateException.class, () -> s.create(null, URL, null));
            }
        });
        test("all key config values loaded from file", () -> {
            Config c = Config.load(Path.of("config.properties")); eq(c.ttl(), Duration.ofHours(24)); eq(c.defaultLimit(), 10L); eq(c.maxLimit(), 1000000L); eq(c.cleanupMillis(), 1000L);
        });
        test("missing config property fails clearly", () -> {
            Path p = Files.createTempFile("shortener-test-", ".properties");
            try { Files.writeString(p, "short.prefix=https://short.local/\n"); rejects(IllegalArgumentException.class, () -> Config.load(p)); }
            finally { Files.delete(p); }
        });
        test("invalid config bounds rejected", () -> rejects(IllegalArgumentException.class, () -> new Config("https://short.local/", Duration.ZERO, 1, 10, 10)));
        test("concurrent opens never exceed visit limit", () -> {
            AtomicInteger browserCalls = new AtomicInteger();
            try (var s = new LinkService(config(), new MutableClock(), uri -> browserCalls.incrementAndGet())) {
                var a = s.create(null, URL, 5L);
                var pool = Executors.newFixedThreadPool(8);
                CountDownLatch start = new CountDownLatch(1);
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                try {
                    for (int i = 0; i < 50; i++) futures.add(pool.submit(() -> {
                        try { start.await(); s.open(a.shortUrl()); }
                        catch (IllegalStateException expected) { /* exhausted */ }
                        catch (Exception ex) { throw new AssertionError(ex); }
                    }));
                    start.countDown();
                    for (var future : futures) future.get(3, TimeUnit.SECONDS);
                    eq(browserCalls.get(), 5); eq(s.list(a.user()).get(0).clicks(), 5L);
                } finally { pool.shutdownNow(); }
            }
        });
        test("CLI integration: create/list, invalid input, user switching and help", () -> {
            try (var s = service(new MutableClock())) {
                StringWriter output = new StringWriter();
                new Console(s, new BufferedReader(new StringReader("list\ncreate " + URL + " 2\nlist\nuser bad\nnew\nlist\nhelp\nexit\n")), new PrintWriter(output, true)).run();
                String text = output.toString(); check(text.contains("Короткая ссылка:")); check(text.contains("| 0/2")); check(text.contains("Ошибка:")); check(text.contains("Новая сессия"));
            }
        });
        test("CLI integration: direct URL, ownership, edit and delete", () -> {
            List<URI> opened = new ArrayList<>();
            try (var s = new LinkService(config(), new MutableClock(), opened::add, () -> "abcdef")) {
                var a = s.create(null, URL, 3L); String shortUrl = a.shortUrl();
                String commands = "user " + UUID.randomUUID() + "\ndelete " + shortUrl + "\nuser " + a.user()
                        + "\nlimit " + shortUrl + " 4\ntarget " + shortUrl + " https://example.net/\n"
                        + shortUrl + "\ndelete " + shortUrl + "\nexit\n";
                StringWriter output = new StringWriter();
                new Console(s, new BufferedReader(new StringReader(commands)), new PrintWriter(output, true)).run();
                check(output.toString().contains("только владельцу")); eq(opened, List.of(URI.create("https://example.net/"))); eq(s.list(a.user()).size(), 0);
            }
        });
        System.out.println("TOTAL: " + passed + " passed, 0 failed");
    }
}
