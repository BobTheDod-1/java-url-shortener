package shortener;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        if (args.length > 1) { System.err.println("Использование: java -jar url-shortener.jar [config.properties]"); System.exit(2); }
        try {
            Config config = Config.load(Path.of(args.length == 1 ? args[0] : "config.properties"));
            try (LinkService service = new LinkService(config, Clock.systemUTC(), new DesktopBrowser())) {
                new Console(service, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                        new PrintWriter(System.out, true, StandardCharsets.UTF_8)).run();
            }
        } catch (Exception ex) { System.err.println("Ошибка запуска: " + ex.getMessage()); System.exit(1); }
    }
}
