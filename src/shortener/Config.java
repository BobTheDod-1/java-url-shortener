package shortener;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

public record Config(String prefix, Duration ttl, long defaultLimit, long maxLimit, long cleanupMillis) {
    public Config {
        URI uri = URI.create(prefix);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !prefix.endsWith("/")) throw new IllegalArgumentException("Некорректный short.prefix");
        if (ttl.isNegative() || ttl.isZero() || defaultLimit < 1 || maxLimit < defaultLimit
                || cleanupMillis < 1) throw new IllegalArgumentException("Некорректные TTL или лимиты");
        ttl.toMillis();
        if (ttl.toMillis() < 1) throw new IllegalArgumentException("TTL должен быть не меньше 1 мс");
    }
    public static Config load(Path path) throws IOException {
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { props.load(reader); }
        return new Config(required(props, "short.prefix"), Duration.parse(required(props, "link.ttl")),
                Long.parseLong(required(props, "clicks.default")), Long.parseLong(required(props, "clicks.max")),
                Long.parseLong(required(props, "cleanup.millis")));
    }
    private static String required(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Не задан " + key);
        return value.trim();
    }
}
