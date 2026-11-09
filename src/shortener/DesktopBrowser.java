package shortener;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

public final class DesktopBrowser implements BrowserOpener {
    @Override public void open(URI uri) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
            throw new IOException("Браузер недоступен в этой среде. Исходный URL: " + uri);
        try { Desktop.getDesktop().browse(uri); }
        catch (RuntimeException ex) { throw new IOException("Не удалось открыть браузер: " + uri, ex); }
    }
}
