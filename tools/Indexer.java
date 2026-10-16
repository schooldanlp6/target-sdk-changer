import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import javax.xml.parsers.SAXParserFactory;
import org.objectweb.asm.*;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Reads the compiled backports of one API level and writes the injection
 * list the on-device patcher executes (inject.txt), one line per entry:
 *
 *   method   <target> <name> <desc> <impl class> <impl name> <impl desc>
 *   static   <target> <name> <desc> <impl class> <impl name> <impl desc>
 *   ctor     <target> <desc> <delegate desc> <impl class> <impl name> <impl desc>
 *   field    <target> <name> <type> <impl class> <impl name> <impl desc>
 *   class    <name>
 *
 * Names are dex/JVM style (android/os/Handler). Every injected member and
 * every new android/java class must exist in api-versions.xml at exactly
 * LEVEL. That catches typos and members filed under the wrong level.
 *
 *   java -cp asm.jar tools/Indexer.java CLASSES_DIR api-versions.xml LEVEL out/inject.txt
 */
public class Indexer {
    static final String PKG = "backport/";
    static final Map<String, Integer> classSince = new HashMap<>();
    static final Map<String, Integer> memberSince = new HashMap<>();
    static final List<String> errors = new ArrayList<>();

    static int level(String attr, int fallback) {
        return attr == null ? fallback : Integer.parseInt(attr.split("\\.")[0]);
    }

    static void readApiVersions(File xml) throws Exception {
        SAXParserFactory.newInstance().newSAXParser().parse(xml, new DefaultHandler() {
            String cls;
            int clsSince;

            @Override
            public void startElement(String uri, String local, String q, Attributes a) {
                if (q.equals("class")) {
                    cls = a.getValue("name");
                    clsSince = level(a.getValue("since"), 1);
                    classSince.put(cls, clsSince);
                } else if (q.equals("method") || q.equals("field")) {
                    memberSince.put(cls + "#" + a.getValue("name"), level(a.getValue("since"), clsSince));
                }
            }
        });
    }

    /** What one annotated method asks for. */
    static final class Entry {
        String kind, implName, implDesc, arg; // arg: field name or ctor delegate
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: Indexer CLASSES_DIR api-versions.xml LEVEL out/inject.txt");
            System.exit(2);
        }
        Path root = Paths.get(args[0]);
        readApiVersions(new File(args[1]));
        int lvl = Integer.parseInt(args[2]);
        List<String> lines = new ArrayList<>();

        List<Path> classes;
        try (Stream<Path> s = Files.walk(root)) {
            classes = s.filter(p -> p.toString().endsWith(".class")).sorted().collect(Collectors.toList());
        }
        for (Path p : classes) {
            byte[] data = Files.readAllBytes(p);
            String[] target = {null};
            String[] self = {null};
            List<Entry> entries = new ArrayList<>();
            new ClassReader(data).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int v, int acc, String name, String sig, String sup, String[] ifs) {
                    self[0] = name;
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    if (!desc.equals("Lbackport/Backport;")) return null;
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String n, Object value) {
                            target[0] = ((String) value).replace('.', '/');
                        }
                    };
                }

                @Override
                public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String adesc, boolean visible) {
                            String kind;
                            switch (adesc) {
                                case "Lbackport/Inject;": kind = "method"; break;
                                case "Lbackport/InjectStatic;": kind = "static"; break;
                                case "Lbackport/InjectConstructor;": kind = "ctor"; break;
                                case "Lbackport/InjectField;": kind = "field"; break;
                                default: return null;
                            }
                            Entry e = new Entry();
                            e.kind = kind;
                            e.implName = name;
                            e.implDesc = desc;
                            entries.add(e);
                            if ((acc & Opcodes.ACC_STATIC) == 0 || (acc & Opcodes.ACC_PUBLIC) == 0) {
                                errors.add(self[0] + "." + name + ": backport methods must be public static");
                            }
                            return new AnnotationVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(String n, Object value) {
                                    e.arg = (String) value;
                                }
                            };
                        }
                    };
                }
            }, ClassReader.SKIP_CODE);

            String cls = self[0];
            if (cls.startsWith(PKG + "util/") || cls.equals(PKG + "Backport") || cls.startsWith(PKG + "Inject")) {
                continue; // runtime helpers / annotations
            }
            if (!cls.startsWith(PKG)) {
                // a whole new framework class (android.graphics.Insets, ...)
                String outer = cls.contains("$") ? cls : null;
                Integer since = classSince.get(cls);
                if (since == null && outer == null) {
                    errors.add(cls + ": new class is not a public API in api-versions.xml");
                } else if (since != null && since != lvl) {
                    errors.add(cls + ": api-versions.xml says API " + since + ", but it's in api" + lvl);
                }
                lines.add("class " + cls);
                continue;
            }
            if (!entries.isEmpty() && target[0] == null) {
                errors.add(cls + ": has @Inject* methods but no @Backport(\"target\") on the class");
                continue;
            }
            for (Entry e : entries) lines.add(line(lvl, target[0], cls, e));
        }

        if (!errors.isEmpty()) {
            errors.forEach(err -> System.err.println("error: " + err));
            System.exit(1);
        }
        Path out = Paths.get(args[3]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.printf("api%d: %d entries -> %s%n", lvl, lines.size(), out);
    }

    static String line(int lvl, String target, String impl, Entry e) {
        Type[] params = Type.getArgumentTypes(e.implDesc);
        Type ret = Type.getReturnType(e.implDesc);
        String selfType = "L" + target + ";";
        String apiKey, out;
        switch (e.kind) {
            case "method": {
                if (params.length == 0 || !params[0].getDescriptor().equals(selfType)) {
                    errors.add(impl + "." + e.implName + ": first parameter must be " + target.replace('/', '.'));
                    return "";
                }
                String desc = Type.getMethodDescriptor(ret, Arrays.copyOfRange(params, 1, params.length));
                apiKey = target + "#" + e.implName + desc;
                out = String.join(" ", "method", target, e.implName, desc, impl, e.implName, e.implDesc);
                break;
            }
            case "static":
                apiKey = target + "#" + e.implName + e.implDesc;
                out = String.join(" ", "static", target, e.implName, e.implDesc, impl, e.implName, e.implDesc);
                break;
            case "ctor": {
                if (params.length == 0 || !params[0].getDescriptor().equals(selfType) || ret != Type.VOID_TYPE) {
                    errors.add(impl + "." + e.implName + ": must be void and take " + target.replace('/', '.') + " first");
                    return "";
                }
                Type[] ctorParams = Arrays.copyOfRange(params, 1, params.length);
                String desc = Type.getMethodDescriptor(Type.VOID_TYPE, ctorParams);
                Type[] delegate = Type.getArgumentTypes(e.arg);
                if (delegate.length > ctorParams.length
                        || !Arrays.equals(delegate, Arrays.copyOf(ctorParams, delegate.length))) {
                    errors.add(impl + "." + e.implName + ": delegate " + e.arg + " must be a prefix of " + desc);
                }
                apiKey = target + "#<init>" + desc;
                out = String.join(" ", "ctor", target, desc, e.arg, impl, e.implName, e.implDesc);
                break;
            }
            default: { // field
                if (params.length != 0 || ret == Type.VOID_TYPE) {
                    errors.add(impl + "." + e.implName + ": @InjectField method takes no parameters and returns the value");
                    return "";
                }
                apiKey = target + "#" + e.arg;
                out = String.join(" ", "field", target, e.arg, ret.getDescriptor(), impl, e.implName, e.implDesc);
                break;
            }
        }
        Integer since = memberSince.get(apiKey);
        if (since == null) {
            errors.add(impl + "." + e.implName + ": " + apiKey + " is not a public API in api-versions.xml");
        } else if (since != lvl) {
            errors.add(impl + "." + e.implName + ": " + apiKey + " is API " + since + ", not " + lvl);
        }
        return out;
    }
}
