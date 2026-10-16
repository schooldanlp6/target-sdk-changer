package backport.patcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** One line of a level's inject.txt (written by tools/Indexer.java). */
final class Injection {
    enum Kind { METHOD, STATIC, CTOR, FIELD, CLASS }

    final Kind kind;
    /** dex type names, e.g. "Landroid/os/Handler;" */
    final String target;
    final String name;
    final String desc;
    final String delegateDesc;
    final String implClass;
    final String implName;
    final String implDesc;

    private Injection(Kind kind, String target, String name, String desc, String delegateDesc,
                      String implClass, String implName, String implDesc) {
        this.kind = kind;
        this.target = target;
        this.name = name;
        this.desc = desc;
        this.delegateDesc = delegateDesc;
        this.implClass = implClass;
        this.implName = implName;
        this.implDesc = implDesc;
    }

    static String type(String internalName) {
        return "L" + internalName + ";";
    }

    /** "Landroid/os/Handler;#createAsync(Landroid/os/Looper;)Landroid/os/Handler;" */
    String key() {
        return target + "#" + name + desc;
    }

    @Override
    public String toString() {
        return kind.name().toLowerCase() + " " + key();
    }

    static List<Injection> read(Path file) throws IOException {
        List<Injection> out = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split(" ");
            switch (f[0]) {
                case "method":
                    out.add(new Injection(Kind.METHOD, type(f[1]), f[2], f[3], null, type(f[4]), f[5], f[6]));
                    break;
                case "static":
                    out.add(new Injection(Kind.STATIC, type(f[1]), f[2], f[3], null, type(f[4]), f[5], f[6]));
                    break;
                case "ctor":
                    out.add(new Injection(Kind.CTOR, type(f[1]), "<init>", f[2], f[3], type(f[4]), f[5], f[6]));
                    break;
                case "field":
                    // name = field name, desc = field type
                    out.add(new Injection(Kind.FIELD, type(f[1]), f[2], f[3], null, type(f[4]), f[5], f[6]));
                    break;
                case "class":
                    out.add(new Injection(Kind.CLASS, type(f[1]), null, null, null, null, null, null));
                    break;
                default:
                    throw new IOException(file + ": unknown entry: " + line);
            }
        }
        return out;
    }
}
