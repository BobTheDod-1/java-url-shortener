package shortener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.UUID;

/** Console is independent of System.in/System.out and a real desktop. */
public final class Console {
    private final LinkService service;
    private final BufferedReader input;
    private final PrintWriter output;
    private UUID user;
    public Console(LinkService service, BufferedReader input, PrintWriter output) {
        this.service = service;
        this.input = input;
        this.output = output;
    }
    private static final String HELP = """
            create <http(s)://url> [limit] — создать ссылку; UUID появится при первом создании
            user <UUID> — выбрать существующий UUID; new — начать новую сессию
            list — мои ссылки; open <short-url> — открыть в браузере
            limit <short-url> <число> — изменить лимит; target <short-url> <URL> — изменить адрес
            delete <short-url> — удалить свою ссылку; notifications — уведомления
            help — помощь; exit — выход. Можно ввести короткую ссылку без команды open.
            Пример: create https://example.org/article 3
            """;
    public void run() throws IOException {
        output.println(HELP);
        for (;;) {
            if (user != null) for (String note : service.drainNotifications(user)) output.println("Уведомление: " + note);
            output.print("> ");
            output.flush();
            String line = input.readLine();
            if (line == null) break;
            String[] words = line.trim().split("\\s+");
            if (words[0].isEmpty()) continue;
            try {
                switch (words[0]) {
                    case "exit" -> { count(words, 1); output.flush(); return; }
                    case "help" -> { count(words, 1); output.println(HELP); }
                    case "new" -> { count(words, 1); user = null; output.println("Новая сессия. UUID будет создан вместе со ссылкой."); }
                    case "user" -> { count(words, 2); user = UUID.fromString(words[1]); output.println("UUID: " + user); }
                    case "create" -> {
                        if (words.length != 2 && words.length != 3) throw new IllegalArgumentException("create <URL> [limit]");
                        var created = service.create(user, words[1], words.length == 3 ? Long.valueOf(words[2]) : null);
                        user = created.user();
                        output.println("UUID: " + user);
                        output.println("Короткая ссылка: " + created.shortUrl());
                    }
                    case "list" -> {
                        count(words, 1); requireUser();
                        var links = service.list(user);
                        if (links.isEmpty()) output.println("Активных ссылок нет");
                        for (Link link : links) output.println(service.shortUrl(link) + " -> " + link.target()
                                + " | " + link.clicks() + "/" + link.limit() + " | до " + link.expiresAt());
                    }
                    case "open" -> { count(words, 2); output.println("Открыто: " + service.open(words[1])); }
                    case "limit" -> { count(words, 3); requireUser(); service.setLimit(user, words[1], Long.parseLong(words[2])); output.println("Лимит обновлён"); }
                    case "target" -> { count(words, 3); requireUser(); service.setTarget(user, words[1], words[2]); output.println("Адрес обновлён"); }
                    case "delete" -> { count(words, 2); requireUser(); service.delete(user, words[1]); output.println("Ссылка удалена"); }
                    case "notifications" -> {
                        count(words, 1); requireUser();
                        for (String note : service.drainNotifications(user)) output.println(note);
                    }
                    default -> {
                        if (words.length == 1 && words[0].startsWith("https://")) output.println("Открыто: " + service.open(words[0]));
                        else throw new IllegalArgumentException("Неизвестная команда. Введите help");
                    }
                }
            } catch (Exception ex) { output.println("Ошибка: " + ex.getMessage()); }
        }
        output.flush();
    }
    private void requireUser() { if (user == null) throw new IllegalStateException("Сначала create или user <UUID>"); }
    private static void count(String[] words, int expected) {
        if (words.length != expected) throw new IllegalArgumentException("Неверное число аргументов. Введите help");
    }
}
