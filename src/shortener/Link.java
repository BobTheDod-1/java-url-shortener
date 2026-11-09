package shortener;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/** Immutable snapshot: clients cannot bypass the service's counters or ownership checks. */
public record Link(String code, UUID owner, URI target, long limit, long clicks, Instant expiresAt) {
    public Link withVisit() { return new Link(code, owner, target, limit, clicks + 1, expiresAt); }
    public Link withLimit(long value) { return new Link(code, owner, target, value, clicks, expiresAt); }
    public Link withTarget(URI value) { return new Link(code, owner, value, limit, clicks, expiresAt); }
}
