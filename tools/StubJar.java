import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.objectweb.asm.*;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Builds a compile-only android.jar for one API level.
 *
 * Takes the newest SDK android.jar and drops every class, method, field and
 * interface that api-versions.xml says was added after LEVEL. Backports for
 * level L are compiled against the stub for L-1, so javac rejects any use of
 * an API the phone might not have.
 *
 *   java -cp asm.jar tools/StubJar.java android.jar api-versions.xml LEVEL out.jar
 */
public class StubJar {
    /** "android/view/View" -> since */
    static final Map<String, Integer> classSince = new HashMap<>();
    /** "android/view/View#setFoo(I)V" or "android/view/View#FIELD" -> since */
    static final Map<String, Integer> memberSince = new HashMap<>();
    /** "android/view/View>android/foo/Iface" -> since */
    static final Map<String, Integer> ifaceSince = new HashMap<>();
    /** "java/lang/reflect/Executable" -> "java/lang/reflect/AccessibleObject" */
    static final Map<String, String> superOf = new HashMap<>();

    static int level(String attr, int fallback) {
        if (attr == null) return fallback;
        return Integer.parseInt(attr.split("\\.")[0]);
    }

    static void readApiVersions(File xml) throws Exception {
        SAXParserFactory.newInstance().newSAXParser().parse(xml, new DefaultHandler() {
            String cls;
            int clsSince;

            @Override
            public void startElement(String uri, String local, String q, Attributes a) {
                switch (q) {
                    case "class":
                        cls = a.getValue("name");
                        clsSince = level(a.getValue("since"), 1);
                        classSince.put(cls, clsSince);
                        break;
                    case "method":
                    case "field":
                        memberSince.put(cls + "#" + a.getValue("name"), level(a.getValue("since"), clsSince));
                        break;
                    case "extends":
                        if (a.getValue("removed") == null) superOf.put(cls, a.getValue("name"));
                        break;
                    case "implements":
                        ifaceSince.put(cls + ">" + a.getValue("name"), level(a.getValue("since"), clsSince));
                        break;
                    default:
                        break;
                }
            }
        });
    }

    /**
     * A class signature is "<type params>" + superclass + one entry per
     * interface, in the same order as the interfaces array. Rebuild it
     * with only the kept interfaces.
     */
    static String dropInterfaces(String sig, String[] ifaces, List<String> keep) {
        if (sig == null) return null;
        int pos = 0;
        if (sig.charAt(0) == '<') pos = skipTo(sig, 0, '>') + 1;
        List<String> parts = new ArrayList<>();
        while (pos < sig.length()) {
            int end = skipTo(sig, pos, ';') + 1;
            parts.add(sig.substring(pos, end));
            pos = end;
        }
        if (parts.size() != ifaces.length + 1) return null; // unexpected shape: drop generics
        StringBuilder out = new StringBuilder(sig.substring(0, sig.length() - String.join("", parts).length()));
        out.append(parts.get(0));
        for (int i = 0; i < ifaces.length; i++) {
            if (keep.contains(ifaces[i])) out.append(parts.get(i + 1));
        }
        return out.toString();
    }

    /** Swap the superclass entry of a class signature (keeps type parameters). */
    static String replaceSuper(String sig, String superclass) {
        if (sig == null) return null;
        int pos = sig.charAt(0) == '<' ? skipTo(sig, 0, '>') + 1 : 0;
        int end = skipTo(sig, pos, ';') + 1;
        return sig.substring(0, pos) + "L" + superclass + ";" + sig.substring(end);
    }

    /** Index of the next `stop` char at angle-bracket depth 0, starting at `from`. */
    static int skipTo(String s, int from, char stop) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                if (depth == 0 && stop == '>') return i;
            } else if (c == stop && depth == 0) return i;
        }
        throw new IllegalArgumentException("bad signature: " + s);
    }

    static byte[] strip(byte[] in, int lvl) {
        ClassReader reader = new ClassReader(in);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            String name;

            @Override
            public void visit(int version, int access, String name, String sig, String sup, String[] ifaces) {
                this.name = name;
                List<String> keep = new ArrayList<>();
                for (String i : ifaces) {
                    if (ifaceSince.getOrDefault(name + ">" + i, 1) <= lvl
                            && classSince.getOrDefault(i, 1) <= lvl) keep.add(i);
                }
                String signature = keep.size() == ifaces.length ? sig : dropInterfaces(sig, ifaces, keep);
                // superclass newer than the level (e.g. Executable, API 26): use its ancestor
                String superclass = sup;
                while (superclass != null && classSince.getOrDefault(superclass, 1) > lvl) {
                    superclass = superOf.get(superclass);
                }
                if (superclass != null && !superclass.equals(sup)) {
                    signature = replaceSuper(signature, superclass);
                }
                super.visit(version, access, name, signature, superclass, keep.toArray(new String[0]));
            }

            @Override
            public MethodVisitor visitMethod(int acc, String n, String desc, String sig, String[] ex) {
                if (memberSince.getOrDefault(name + "#" + n + desc, 1) > lvl) return null;
                return super.visitMethod(acc, n, desc, sig, ex);
            }

            @Override
            public FieldVisitor visitField(int acc, String n, String desc, String sig, Object value) {
                if (memberSince.getOrDefault(name + "#" + n, 1) > lvl) return null;
                return super.visitField(acc, n, desc, sig, value);
            }

            @Override
            public void visitInnerClass(String n, String outer, String inner, int acc) {
                if (classSince.getOrDefault(n, 1) <= lvl) super.visitInnerClass(n, outer, inner, acc);
            }
        }, 0);
        return writer.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: StubJar android.jar api-versions.xml LEVEL out.jar");
            System.exit(2);
        }
        int lvl = Integer.parseInt(args[2]);
        readApiVersions(new File(args[1]));
        int kept = 0, dropped = 0;
        Path out = Paths.get(args[3]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        try (ZipFile in = new ZipFile(args[0]);
             ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
            for (ZipEntry e : Collections.list(in.entries())) {
                String n = e.getName();
                if (!n.endsWith(".class")) continue;
                String cls = n.substring(0, n.length() - 6);
                if (classSince.getOrDefault(cls, 1) > lvl) {
                    dropped++;
                    continue;
                }
                byte[] data;
                try (InputStream is = in.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                zout.putNextEntry(new ZipEntry(n));
                zout.write(strip(data, lvl));
                zout.closeEntry();
                kept++;
            }
        }
        System.out.printf("android-%d stub: %d classes kept, %d newer classes dropped -> %s%n",
                lvl, kept, dropped, out);
    }
}
