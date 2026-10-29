package io.github.rohan9405.feed;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plain HTTPS GETs with short timeouts and a size cap, so one slow poster host cannot stall a refresh. */
final class Net {
    private static final int TIMEOUT_MS = 8000;

    private Net() {
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CleanYourTV-Feed/1.0");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                return read(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] read(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("too large");
        }
        return out.toByteArray();
    }
}
