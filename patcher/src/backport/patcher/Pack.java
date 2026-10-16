package backport.patcher;

import java.io.*;
import java.util.Enumeration;
import java.util.zip.*;

/**
 * Writes a jar, so the install script doesn't need a zip tool on the phone.
 *
 *   Pack <out.jar> [--from <original.jar>] <name>=<file>...
 *
 * With --from, every entry of the original jar except classes*.dex is
 * copied first (Java resources, META-INF), then the given files are added.
 * Entries are stored uncompressed, which is what ART expects for dex in
 * framework jars.
 */
public final class Pack {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Pack <out.jar> [--from <original.jar>] <name>=<file>...");
            System.exit(2);
        }
        int i = 1;
        String from = null;
        if (args[1].equals("--from")) {
            from = args[2];
            i = 3;
        }
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(args[0]))) {
            if (from != null) {
                try (ZipFile orig = new ZipFile(from)) {
                    Enumeration<? extends ZipEntry> entries = orig.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        if (e.isDirectory() || e.getName().matches("classes\\d*\\.dex")) continue;
                        try (InputStream in = orig.getInputStream(e)) {
                            put(zip, e.getName(), readAll(in));
                        }
                    }
                }
            }
            for (; i < args.length; i++) {
                int eq = args[i].indexOf('=');
                try (InputStream in = new FileInputStream(args[i].substring(eq + 1))) {
                    put(zip, args[i].substring(0, eq), readAll(in));
                }
            }
        }
    }

    static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
