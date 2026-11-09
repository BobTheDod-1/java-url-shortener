package shortener;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** In-memory single-process core. All mutations and cleanup are serialized on this instance. */
public final class LinkService implements AutoCloseable {
    public record Created(UUID user, String shortUrl) { }
    private final Config config;
    private final Clock clock;
    private final BrowserOpener browser;
    private final Supplier<String> codes;
    private final Map<String, Link> links = new HashMap<>();
    private final Set<String> usedCodes = new HashSet<>();
    private final Map<UUID, List<String>> notifications = new HashMap<>();
    private final ScheduledExecutorService cleaner;

    public LinkService(Config config, Clock clock, BrowserOpener browser) {
        this(config, clock, browser, () -> UUID.randomUUID().toString().replace("-", "").substring(0, 10));
    }
    public LinkService(Config config, Clock clock, BrowserOpener browser, Supplier<String> codes) {
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.browser = Objects.requireNonNull(browser);
        this.codes = Objects.requireNonNull(codes);
        cleaner = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "link-expiration");
            thread.setDaemon(true);
            return thread;
        });
        cleaner.scheduleWithFixedDelay(this::cleanup, config.cleanupMillis(),
                config.cleanupMillis(), TimeUnit.MILLISECONDS);
    }
    public static URI validateUrl(String text) {
        URI uri;
        try { uri = URI.create(text); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Некорректный URL", ex); }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getPort() > 65535
                || uri.getPort() == 0) throw new IllegalArgumentException("Нужен абсолютный http(s) URL без логина/пароля");
        return uri;
    }
    private void validateLimit(long limit) {
        if (limit < 1 || limit > config.maxLimit())
            throw new IllegalArgumentException("Лимит: от 1 до " + config.maxLimit());
    }
    public synchronized Created create(UUID user, String target, Long requestedLimit) {
        URI uri = validateUrl(target);
        long limit = requestedLimit == null ? config.defaultLimit() : requestedLimit;
        validateLimit(limit);
        UUID owner = user == null ? UUID.randomUUID() : user;
        String code = null;
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = codes.get();
            if (candidate != null && candidate.matches("[A-Za-z0-9]{6,32}") && !usedCodes.contains(candidate)) {
                code = candidate;
                break;
            }
        }
        if (code == null) throw new IllegalStateException("Не удалось создать уникальный код");
        Instant expiresAt = clock.instant().plus(config.ttl());
        usedCodes.add(code);
        links.put(code, new Link(code, owner, uri, limit, 0, expiresAt));
        return new Created(owner, config.prefix() + code);
    }
    private String code(String shortUrl) {
        if (shortUrl == null || !shortUrl.startsWith(config.prefix()))
            throw new IllegalArgumentException("Нужна короткая ссылка с префиксом " + config.prefix());
        String result = shortUrl.substring(config.prefix().length());
        if (!result.matches("[A-Za-z0-9]{6,32}")) throw new IllegalArgumentException("Некорректная короткая ссылка");
        return result;
    }
    private void notifyOwner(Link link, String message) {
        notifications.computeIfAbsent(link.owner(), ignored -> new ArrayList<>())
                .add(config.prefix() + link.code() + ": " + message + ". Создайте новую ссылку.");
    }
    public synchronized void cleanup() {
        Instant now = clock.instant();
        var iterator = links.values().iterator();
        while (iterator.hasNext()) {
            Link link = iterator.next();
            if (!now.isBefore(link.expiresAt())) {
                iterator.remove();
                notifyOwner(link, "Срок действия истёк, ссылка автоматически удалена");
            }
        }
    }
    private Link active(String url) {
        cleanup();
        Link link = links.get(code(url));
        if (link == null) throw new IllegalArgumentException("Ссылка не найдена, удалена или истёк срок. Создайте новую ссылку.");
        return link;
    }
    private Link owned(UUID user, String url) {
        Link link = active(url);
        if (!link.owner().equals(user)) throw new SecurityException("Изменение доступно только владельцу");
        return link;
    }
    public synchronized URI open(String url) throws IOException {
        Link link = active(url);
        if (link.clicks() >= link.limit())
            throw new IllegalStateException("Лимит переходов исчерпан. Создайте новую ссылку или измените лимит.");
        // A failed Desktop dispatch does not consume a visit. Synchronization prevents overrun.
        browser.open(link.target());
        Link visited = link.withVisit();
        links.put(link.code(), visited);
        if (visited.clicks() == visited.limit()) notifyOwner(visited, "Лимит переходов исчерпан, ссылка заблокирована");
        return link.target();
    }
    public synchronized void setLimit(UUID user, String url, long limit) {
        Link link = owned(user, url);
        validateLimit(limit);
        if (limit < link.clicks()) throw new IllegalArgumentException("Лимит не может быть меньше числа переходов");
        links.put(link.code(), link.withLimit(limit));
        if (limit == link.clicks() && link.limit() != limit) notifyOwner(link, "Лимит переходов исчерпан, ссылка заблокирована");
    }
    public synchronized void setTarget(UUID user, String url, String target) {
        Link link = owned(user, url);
        links.put(link.code(), link.withTarget(validateUrl(target)));
    }
    public synchronized void delete(UUID user, String url) {
        Link link = owned(user, url);
        links.remove(link.code());
    }
    public synchronized List<Link> list(UUID user) {
        cleanup();
        return links.values().stream().filter(link -> link.owner().equals(user))
                .sorted(Comparator.comparing(Link::code)).toList();
    }
    public synchronized List<String> drainNotifications(UUID user) {
        cleanup();
        List<String> result = notifications.remove(user);
        return result == null ? List.of() : List.copyOf(result);
    }
    public String shortUrl(Link link) { return config.prefix() + link.code(); }
    @Override public void close() { cleaner.shutdownNow(); }
}
