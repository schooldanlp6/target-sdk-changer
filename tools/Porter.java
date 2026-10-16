import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.SAXParserFactory;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Porter, step 1: analysis.
 *
 * For every public API added in levels FROM..TO, decide whether the real
 * AOSP implementation (NEW jar) can run on the board's framework (BASE jar).
 * Everything the code references is followed; whatever BASE lacks is pulled
 * in too, unless it is native code or a new instance field on a class that
 * already exists on the phone.
 *
 *   java -cp asm.jar:asm-tree.jar tools/Porter.java BASE.jar NEW.jar api-versions.xml FROM TO report.txt \
 *        [BASE_CORE_STUB.jar]
 *
 * The AOSP framework jars don't contain the Java core library (java.*,
 * javax.*, ...). BASE_CORE_STUB (build/stubs/android-FROM-1.jar) supplies
 * what the phone has there. New core-library APIs can't be ported from
 * these jars and show up as "class missing in AOSP jar".
 */
public class Porter {
    static final class Jar {
        final ZipFile zip;
        final Map<String, ClassNode> headers = new HashMap<>();
        final Map<String, ClassNode> full = new HashMap<>();

        Jar(String path) throws IOException {
            zip = new ZipFile(path);
            readHeaders(zip, false);
        }

        /** Add class headers from another jar for classes this one lacks (signatures only). */
        void addCore(String path) throws IOException {
            readHeaders(new ZipFile(path), true);
        }

        private void readHeaders(ZipFile z, boolean onlyMissing) throws IOException {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (!e.getName().endsWith(".class")) continue;
                if (onlyMissing && headers.containsKey(e.getName().replace(".class", ""))) continue;
                ClassNode n = new ClassNode();
                try (InputStream in = z.getInputStream(e)) {
                    new ClassReader(in).accept(n, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                }
                headers.put(n.name, n);
            }
        }

        ClassNode code(String name) throws IOException {
            ClassNode n = full.get(name);
            if (n == null && headers.containsKey(name)) {
                n = new ClassNode();
                try (InputStream in = zip.getInputStream(zip.getEntry(name + ".class"))) {
                    new ClassReader(in).accept(n, ClassReader.SKIP_DEBUG);
                }
                full.put(name, n);
            }
            return n;
        }

        /** Class declaring owner.name(+desc), searching supertypes; null if none. */
        String resolve(String owner, String name, String desc, boolean field) {
            Deque<String> todo = new ArrayDeque<>();
            todo.add(owner);
            Set<String> seen = new HashSet<>();
            while (!todo.isEmpty()) {
                String c = todo.pop();
                if (!seen.add(c)) continue;
                ClassNode n = headers.get(c);
                if (n == null) continue;
                if (field) {
                    for (FieldNode f : n.fields) if (f.name.equals(name)) return c;
                } else {
                    for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) return c;
                }
                if (n.superName != null) todo.add(n.superName);
                todo.addAll(n.interfaces);
            }
            return null;
        }
    }

    static Jar base, neu;
    /** SDK android.jar (--api-jar): public signatures incl. static flags, for stubs */
    static Jar api;

    /**
     * Anonymous classes (Foo$1) are numbered by the compiler, so AOSP 11's
     * Foo$1 is unrelated to the phone's Foo$1. They are always treated as
     * new and renamed to Foo$Bp1 when ported.
     */
    static boolean anonymous(String c) {
        return c.matches(".*\\$\\d+");
    }

    /** Does the phone (BASE) have this class, in the sense of "the same class"? */
    static boolean inBase(String c) {
        return base.headers.containsKey(c) && !anonymous(c);
    }
    /** --experimental: allow new instance fields on classes the phone already has */
    static boolean experimental;
    /**
     * --all (implies --experimental): also port AOSP static initialisers for
     * new static fields on existing classes, and stub framework methods that
     * are native or whose code isn't in the AOSP jar.
     */
    static boolean all;
    /** method items that get a stub body instead of AOSP code (--all) */
    static final Set<String> stubs = new HashSet<>();
    /** method item -> true if called with invokestatic (from call sites in ported code) */
    static final Map<String, Boolean> callIsStatic = new HashMap<>();
    /** existing classes that get AOSP's static initialiser as $bp$clinit (--all) */
    static final Set<String> clinits = new TreeSet<>();

    /** Packages under android/ and com/android/ that live in other boot jars, not framework.jar. */
    static final String[] OTHER_JARS = {
        "android/icu/",                  // core-icu4j.jar
        "android/system/",               // core-libart.jar
        "android/test/",                 // legacy-test.jar
        "com/android/okhttp/",           // okhttp.jar
        "com/android/org/conscrypt/",    // conscrypt.jar
        "com/android/org/bouncycastle/", // bouncycastle.jar
        "com/android/org/kxml2/",        // core-libart.jar
        "com/android/i18n/",             // core-icu4j.jar (newer releases)
    };

    /** Only framework.jar is patched: java.*, dalvik.*, ICU etc. can't be merged into or stubbed. */
    static boolean framework(String c) {
        if (!c.startsWith("android/") && !c.startsWith("com/android/")) return false;
        for (String p : OTHER_JARS) if (c.startsWith(p)) return false;
        return true;
    }

    /** Can a stub for owner.name+desc be added (--all)? */
    static boolean stubbable(String owner, String name) {
        if (!all || !framework(owner) || name.equals("<init>") || name.equals("<clinit>")) return false;
        ClassNode h = inBase(owner) ? base.headers.get(owner) : neu.headers.get(owner);
        return h != null && (h.access & Opcodes.ACC_INTERFACE) == 0;
    }
    /** item -> null (portable) or reason it can't be ported */
    static final Map<String, String> verdict = new HashMap<>();
    /** item -> items it needs */
    static final Map<String, Set<String>> deps = new HashMap<>();
    static final Deque<String> work = new ArrayDeque<>();

    static void need(String item) {
        if (!verdict.containsKey(item) && !deps.containsKey(item)) work.add(item);
    }

    /** Items: "C owner", "M owner name desc", "F owner name". */
    static void analyse(String item) throws IOException {
        String[] p = item.split(" ");
        Set<String> d = new LinkedHashSet<>();
        deps.put(item, d);
        if (p[0].equals("K")) {
            analyseClinit(item, p[1], d);
        } else if (p[0].equals("C")) {
            analyseClass(item, p[1], d);
        } else if (p[0].equals("F")) {
            analyseField(item, p[1], p[2], d);
        } else {
            analyseMethod(item, p[1], p[2], p[3], d);
        }
        d.forEach(Porter::need);
    }

    /**
     * "K owner": AOSP's static initialiser of an existing class, run once more
     * (as owner.$bp$clinit) after the phone's own one. Stores into fields the
     * phone already has are dropped, so only the new fields get values.
     */
    static void analyseClinit(String item, String owner, Set<String> d) throws IOException {
        MethodNode clinit = null;
        for (MethodNode m : neu.code(owner).methods) if (m.name.equals("<clinit>")) clinit = m;
        if (clinit == null) return;
        if (((base.headers.get(owner).access ^ neu.headers.get(owner).access) & Opcodes.ACC_INTERFACE) != 0) {
            verdict.put(item, "class/interface changed between versions: " + owner);
            return;
        }
        for (AbstractInsnNode insn : clinit.instructions) {
            if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTSTATIC
                    && ((FieldInsnNode) insn).owner.equals(owner)
                    && base.resolve(owner, ((FieldInsnNode) insn).name, null, true) != null) {
                continue; // phone's own field: the store is dropped
            }
            refs(insn, d);
        }
        for (TryCatchBlockNode t : clinit.tryCatchBlocks) if (t.type != null) d.add("C " + t.type);
    }

    static void analyseClass(String item, String c, Set<String> d) throws IOException {
        if (c.startsWith("[")) c = Type.getType(c).getElementType().getInternalName();
        if (inBase(c) || c.length() == 1) return;
        ClassNode n = neu.code(c);
        if (n == null) {
            verdict.put(item, "class missing in AOSP jar");
            return;
        }
        if (n.superName != null) d.add("C " + n.superName);
        for (String i : n.interfaces) d.add("C " + i);
        for (MethodNode m : n.methods) d.add("M " + c + " " + m.name + " " + m.desc);
        for (FieldNode f : n.fields) d.add("F " + c + " " + f.name);
    }

    static void analyseField(String item, String owner, String name, Set<String> d) throws IOException {
        d.add("C " + owner);
        if (anonymous(owner)) return; // ported with its whole class
        if (base.resolve(owner, name, null, true) != null) return;
        String decl = neu.resolve(owner, name, null, true);
        if (decl == null) {
            verdict.put(item, "field missing in AOSP jar");
            return;
        }
        if (inBase(decl) && !framework(decl)) {
            verdict.put(item, "new member on " + decl + ", which lives outside framework.jar");
            return;
        }
        if (inBase(decl)) {
            FieldNode f = find(neu.code(decl).fields, name);
            if ((f.access & Opcodes.ACC_STATIC) == 0) {
                // the phone's own constructors never set it: only in experimental builds
                if (!experimental) verdict.put(item, "new instance field on existing class " + decl);
            } else if (f.value == null) {
                // needs AOSP's static initialiser: ported as a whole with --all
                if (all) d.add("K " + decl);
                else verdict.put(item, "new static field with initialiser on existing class " + decl);
            }
        }
        if (!decl.equals(owner)) d.add("F " + decl + " " + name);
    }

    static void analyseMethod(String item, String owner, String name, String desc, Set<String> d) throws IOException {
        if (owner.startsWith("[")) return; // array clone() etc.
        d.add("C " + owner);
        for (Type t : Type.getArgumentTypes(desc)) addType(t, d);
        addType(Type.getReturnType(desc), d);
        if (anonymous(owner)) return; // ported with its whole class
        if (base.resolve(owner, name, desc, false) != null) return;
        String decl = neu.resolve(owner, name, desc, false);
        if (decl == null) {
            // a stub needs to know static vs instance: from a call site or the SDK's android.jar
            if (!callIsStatic.containsKey(item) && api != null && api.headers.containsKey(owner)) {
                for (MethodNode x : api.headers.get(owner).methods) {
                    if (x.name.equals(name) && x.desc.equals(desc)) {
                        callIsStatic.put(item, (x.access & Opcodes.ACC_STATIC) != 0);
                    }
                }
            }
            if (stubbable(owner, name) && callIsStatic.containsKey(item)) stubs.add(item);
            else verdict.put(item, "method missing in AOSP jar");
            return;
        }
        if (!decl.equals(owner)) {
            d.add("M " + decl + " " + name + " " + desc);
            return;
        }
        MethodNode m = null;
        for (MethodNode x : neu.code(decl).methods) if (x.name.equals(name) && x.desc.equals(desc)) m = x;
        if ((m.access & Opcodes.ACC_NATIVE) != 0) {
            if (stubbable(decl, name)) stubs.add(item);
            else verdict.put(item, "native method");
            return;
        }
        if (inBase(decl) && !framework(decl)) {
            verdict.put(item, "new member on " + decl + ", which lives outside framework.jar");
            return;
        }
        if (inBase(decl)) {
            int baseAccess = base.headers.get(decl).access, newAccess = neu.headers.get(decl).access;
            if (((baseAccess ^ newAccess) & Opcodes.ACC_INTERFACE) != 0) {
                verdict.put(item, "class/interface changed between versions: " + decl);
                return;
            }
            if ((m.access & Opcodes.ACC_ABSTRACT) != 0
                    && (baseAccess & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) == 0) {
                verdict.put(item, "abstract method on a class that is concrete on the phone: " + decl);
                return;
            }
            // super(...) / super.x() calls must target the phone's superclass
            String baseSuper = base.headers.get(decl).superName, newSuper = neu.headers.get(decl).superName;
            if (!Objects.equals(baseSuper, newSuper)) {
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn.getOpcode() == Opcodes.INVOKESPECIAL
                            && ((MethodInsnNode) insn).owner.equals(newSuper)) {
                        verdict.put(item, "superclass changed between versions: " + decl);
                        return;
                    }
                }
            }
        }
        if (m.name.equals("<init>") && base.headers.containsKey(decl)) {
            // new constructor on an existing class: fine if it only calls this()/super()
        }
        for (AbstractInsnNode insn : m.instructions) refs(insn, d);
        for (TryCatchBlockNode t : m.tryCatchBlocks) if (t.type != null) d.add("C " + t.type);
    }

    /** What one instruction references. invokedynamic (lambdas) is fine: d8 desugars it. */
    static void refs(AbstractInsnNode insn, Set<String> d) {
        if (insn instanceof MethodInsnNode) {
            MethodInsnNode mi = (MethodInsnNode) insn;
            d.add("M " + mi.owner + " " + mi.name + " " + mi.desc);
            callIsStatic.put("M " + mi.owner + " " + mi.name + " " + mi.desc, mi.getOpcode() == Opcodes.INVOKESTATIC);
        } else if (insn instanceof FieldInsnNode) {
            FieldInsnNode fi = (FieldInsnNode) insn;
            d.add("F " + fi.owner + " " + fi.name);
        } else if (insn instanceof TypeInsnNode) {
            addType(Type.getObjectType(((TypeInsnNode) insn).desc), d);
        } else if (insn instanceof LdcInsnNode && ((LdcInsnNode) insn).cst instanceof Type) {
            addType((Type) ((LdcInsnNode) insn).cst, d);
        } else if (insn instanceof MultiANewArrayInsnNode) {
            addType(Type.getType(((MultiANewArrayInsnNode) insn).desc), d);
        }
    }

    static void addType(Type t, Set<String> d) {
        if (t.getSort() == Type.ARRAY) t = t.getElementType();
        if (t.getSort() == Type.OBJECT) d.add("C " + t.getInternalName());
    }

    static FieldNode find(List<FieldNode> fields, String name) {
        for (FieldNode f : fields) if (f.name.equals(name)) return f;
        return null;
    }

    /** Root cause that blocks item (following deps), or null if portable. */
    static Map<String, String> blocked() {
        Map<String, String> out = new HashMap<>();
        verdict.forEach((k, v) -> { if (v != null) out.put(k, v); });
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
                if (out.containsKey(e.getKey())) continue;
                for (String dep : e.getValue()) {
                    String why = out.get(dep);
                    if (why != null) {
                        out.put(e.getKey(), why.startsWith("needs ") ? why : "needs " + dep + ": " + why);
                        changed = true;
                        break;
                    }
                }
            }
        }
        return out;
    }

    /**
     * Writes what the portable APIs need: whole new classes (copied from the
     * AOSP jar) and, for classes the phone already has, partial classes with
     * only the new members (merged into the real class on the phone).
     * merge.txt lists the partial ones.
     */
    static void emit(File dir, List<Api> apis, Map<String, String> blocked) throws IOException {
        Set<String> needed = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        for (Api a : apis) if (!blocked.containsKey(a.item)) todo.add(a.item);
        while (!todo.isEmpty()) {
            String item = todo.pop();
            if (!needed.add(item)) continue;
            for (String d : deps.getOrDefault(item, Collections.emptySet())) todo.add(d);
        }
        Set<String> newClasses = new TreeSet<>();
        // existing class -> "M name desc" (AOSP method), "S name desc" (stub), "F name" (field)
        Map<String, Set<String>> members = new TreeMap<>();
        Map<String, Set<String>> newClassStubs = new HashMap<>(); // new class -> stubbed "name desc"
        for (String item : needed) {
            String[] p = item.split(" ");
            String owner = p[1];
            if (p[0].equals("K")) {
                clinits.add(owner);
                members.computeIfAbsent(owner, k -> new TreeSet<>());
                continue;
            }
            if (owner.startsWith("[") || !neu.headers.containsKey(owner)) continue;
            boolean existing = inBase(owner);
            if (p[0].equals("C")) {
                if (!existing) newClasses.add(owner);
            } else if (p[0].equals("M") && stubs.contains(item)) {
                if (existing) members.computeIfAbsent(owner, k -> new TreeSet<>()).add("S " + p[2] + " " + p[3]);
                else newClassStubs.computeIfAbsent(owner, k -> new HashSet<>()).add(p[2] + " " + p[3]);
            } else if (existing) {
                boolean have = p[0].equals("M") ? base.resolve(owner, p[2], p[3], false) != null
                        : base.resolve(owner, p[2], null, true) != null;
                boolean declared = p[0].equals("M") ? neu.resolve(owner, p[2], p[3], false) != null
                        && neu.resolve(owner, p[2], p[3], false).equals(owner)
                        : owner.equals(neu.resolve(owner, p[2], null, true));
                if (!have && declared) {
                    members.computeIfAbsent(owner, k -> new TreeSet<>())
                            .add(p[0].equals("M") ? "M " + p[2] + " " + p[3] : "F " + p[2]);
                }
            }
        }
        // every new static field AOSP's initialiser writes must exist on the phone
        for (String c : clinits) {
            for (MethodNode m : neu.code(c).methods) {
                if (!m.name.equals("<clinit>")) continue;
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn.getOpcode() == Opcodes.PUTSTATIC && ((FieldInsnNode) insn).owner.equals(c)
                            && base.resolve(c, ((FieldInsnNode) insn).name, null, true) == null) {
                        members.get(c).add("F " + ((FieldInsnNode) insn).name);
                    }
                }
            }
        }

        Map<String, String> rename = new HashMap<>();
        for (String c : newClasses) {
            if (anonymous(c)) {
                int cut = c.lastIndexOf('$');
                rename.put(c, c.substring(0, cut) + "$Bp" + c.substring(cut + 1));
            }
        }
        SimpleRemapper remapper = new SimpleRemapper(rename);
        for (String c : newClasses) {
            ClassNode n = new ClassNode();
            neu.code(c).accept(n);
            Set<String> st = newClassStubs.getOrDefault(c, Collections.emptySet());
            for (String key : st) {
                String name = key.substring(0, key.indexOf(' ')), desc = key.substring(key.indexOf(' ') + 1);
                MethodNode old = null;
                for (MethodNode m : n.methods) if (m.name.equals(name) && m.desc.equals(desc)) old = m;
                boolean isStatic = old != null ? (old.access & Opcodes.ACC_STATIC) != 0
                        : callIsStatic.get("M " + c + " " + key);
                int access = old != null ? old.access : Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0);
                if (old != null) n.methods.remove(old);
                n.methods.add(stub(access, name, desc));
            }
            ClassWriter w = new ClassWriter(0);
            n.accept(new ClassRemapper(w, remapper));
            write(dir, rename.getOrDefault(c, c), w.toByteArray());
        }
        int stubCount = newClassStubs.values().stream().mapToInt(Set::size).sum();
        for (Map.Entry<String, Set<String>> e : members.entrySet()) {
            String c = e.getKey();
            ClassNode full = neu.code(c);
            ClassNode baseHeader = base.headers.get(c);
            ClassNode part = new ClassNode();
            full.accept(part);
            MethodNode clinit = null;
            for (MethodNode m : part.methods) if (m.name.equals("<clinit>")) clinit = m;
            part.methods.removeIf(m -> !e.getValue().contains("M " + m.name + " " + m.desc));
            part.fields.removeIf(f -> !e.getValue().contains("F " + f.name));
            for (FieldNode f : part.fields) {
                // assigned by $bp$clinit, not by <clinit>: can't stay final
                if ((f.access & Opcodes.ACC_STATIC) != 0 && f.value == null) f.access &= ~Opcodes.ACC_FINAL;
            }
            for (String m : e.getValue()) {
                if (!m.startsWith("S ")) continue;
                String[] q = m.split(" ");
                MethodNode old = null;
                for (MethodNode x : full.methods) if (x.name.equals(q[1]) && x.desc.equals(q[2])) old = x;
                boolean isStatic = old != null ? (old.access & Opcodes.ACC_STATIC) != 0
                        : callIsStatic.get("M " + c + " " + q[1] + " " + q[2]);
                int access = old != null ? old.access : Opcodes.ACC_PUBLIC | (isStatic ? Opcodes.ACC_STATIC : 0);
                part.methods.add(stub(access, q[1], q[2]));
                stubCount++;
            }
            if (clinits.contains(c) && clinit != null) part.methods.add(portedClinit(c, clinit));
            // keep the phone's hierarchy; only members get merged
            part.superName = baseHeader.superName;
            part.interfaces = new ArrayList<>(baseHeader.interfaces);
            part.signature = null;
            ClassWriter w = new ClassWriter(0);
            part.accept(new ClassRemapper(w, remapper));
            write(dir, c, w.toByteArray());
        }
        try (PrintWriter w = new PrintWriter(new File(dir, "merge.txt"))) {
            for (Map.Entry<String, Set<String>> e : members.entrySet()) {
                w.println(e.getKey() + " " + e.getValue().size());
            }
        }
        try (PrintWriter w = new PrintWriter(new File(dir, "clinit.txt"))) {
            for (String c : clinits) w.println(c);
        }
        System.out.printf("emitted %d new classes (%d anonymous renamed), %d partial classes (%d members),"
                        + " %d stubs, %d ported static initialisers -> %s%n",
                newClasses.size(), rename.size(), members.size(),
                members.values().stream().mapToInt(Set::size).sum(), stubCount, clinits.size(), dir);
    }

    /** Stub body: void methods do nothing, others throw UnsupportedOperationException. */
    static MethodNode stub(int access, String name, String desc) {
        access &= ~(Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT);
        MethodNode m = new MethodNode(access, name, desc, null, null);
        if (Type.getReturnType(desc) == Type.VOID_TYPE) {
            m.instructions.add(new InsnNode(Opcodes.RETURN));
            m.maxStack = 0;
        } else {
            m.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
            m.instructions.add(new InsnNode(Opcodes.DUP));
            m.instructions.add(new LdcInsnNode(name + " is not available on this Android version (backport stub)"));
            m.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException",
                    "<init>", "(Ljava/lang/String;)V", false));
            m.instructions.add(new InsnNode(Opcodes.ATHROW));
            m.maxStack = 3;
        }
        int args = Type.getArgumentsAndReturnSizes(desc) >> 2; // includes 1 for `this`
        m.maxLocals = (access & Opcodes.ACC_STATIC) != 0 ? args - 1 : args;
        return m;
    }

    /**
     * Copy of AOSP's <clinit> as private static $bp$clinit()V. Stores into
     * fields the phone already has become pops, so the phone keeps its values.
     */
    static MethodNode portedClinit(String owner, MethodNode clinit) {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                "$bp$clinit", "()V", null, null);
        clinit.accept(m);
        m.access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        m.name = "$bp$clinit";
        m.desc = "()V";
        for (AbstractInsnNode insn : m.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (f.owner.equals(owner) && base.resolve(owner, f.name, null, true) != null) {
                    int size = Type.getType(f.desc).getSize();
                    m.instructions.set(insn, new InsnNode(size == 2 ? Opcodes.POP2 : Opcodes.POP));
                }
            }
        }
        return m;
    }

    static void write(File dir, String cls, byte[] data) throws IOException {
        File f = new File(dir, "classes/" + cls + ".class");
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    static final class Api {
        final String item;
        final int since;
        Api(String item, int since) { this.item = item; this.since = since; }
    }

    static List<Api> readApis(File xml, int from, int to) throws Exception {
        List<Api> apis = new ArrayList<>();
        SAXParserFactory.newInstance().newSAXParser().parse(xml, new DefaultHandler() {
            String cls;
            int clsSince;

            int lvl(Attributes a, int def) {
                String s = a.getValue("since");
                return s == null ? def : Integer.parseInt(s.split("\\.")[0]);
            }

            @Override
            public void startElement(String uri, String local, String q, Attributes a) {
                if (q.equals("class")) {
                    cls = a.getValue("name");
                    clsSince = lvl(a, 1);
                    if (clsSince >= from && clsSince <= to) apis.add(new Api("C " + cls, clsSince));
                } else if ((q.equals("method") || q.equals("field")) && a.getValue("removed") == null) {
                    int since = lvl(a, clsSince);
                    if (since < from || since > to || since == clsSince && clsSince >= from) return;
                    String n = a.getValue("name");
                    if (q.equals("field")) {
                        apis.add(new Api("F " + cls + " " + n, since));
                    } else {
                        int paren = n.indexOf('(');
                        apis.add(new Api("M " + cls + " " + n.substring(0, paren) + " " + n.substring(paren), since));
                    }
                }
            }
        });
        return apis;
    }

    public static void main(String[] args) throws Exception {
        List<String> pos = new ArrayList<>();
        File emitDir = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--experimental")) experimental = true;
            else if (args[i].equals("--all")) experimental = all = true;
            else if (args[i].equals("--api-jar")) api = new Jar(args[++i]);
            else if (args[i].equals("--emit")) emitDir = new File(args[++i]);
            else pos.add(args[i]);
        }
        args = pos.toArray(new String[0]);
        if (args.length != 6 && args.length != 7) {
            System.err.println("usage: Porter BASE.jar NEW.jar api-versions.xml FROM TO report.txt [CORE_STUB.jar]"
                    + " [--experimental] [--emit DIR]");
            System.exit(2);
        }
        base = new Jar(args[0]);
        neu = new Jar(args[1]);
        if (args.length == 7) base.addCore(args[6]);
        int from = Integer.parseInt(args[3]), to = Integer.parseInt(args[4]);
        List<Api> apis = readApis(new File(args[2]), from, to);
        for (Api a : apis) need(a.item);
        while (!work.isEmpty()) {
            String item = work.pop();
            if (!deps.containsKey(item)) analyse(item);
        }
        Map<String, String> blocked = blocked();

        int[] ported = new int[64], stopped = new int[64], present = new int[64];
        Map<String, Integer> causes = new TreeMap<>();
        List<String> lines = new ArrayList<>();
        for (Api a : apis) {
            String[] p = a.item.split(" ");
            boolean inBase = p[0].equals("C") ? base.headers.containsKey(p[1])
                    : base.resolve(p[1], p[2], p[0].equals("M") ? p[3] : null, p[0].equals("F")) != null;
            String why = blocked.get(a.item);
            if (inBase) {
                present[a.since]++;
            } else if (why == null) {
                ported[a.since]++;
                lines.add(String.format("PORT  %d  %s", a.since, a.item));
            } else {
                stopped[a.since]++;
                String root = why.contains(": ") ? why.substring(why.lastIndexOf(": ") + 2) : why;
                causes.merge(root, 1, Integer::sum);
                lines.add(String.format("STOP  %d  %s  <- %s", a.since, a.item, why));
            }
        }
        int pulled = 0;
        for (String item : deps.keySet()) {
            String[] p = item.split(" ");
            if (p[0].equals("C") && !base.headers.containsKey(p[1]) && neu.headers.containsKey(p[1])
                    && !blocked.containsKey(item)) pulled++;
        }

        StringBuilder sum = new StringBuilder();
        sum.append(String.format("Porter: %s -> %s, API %d..%d, %s%n", new File(args[0]).getName(),
                new File(args[1]).getName(), from, to, all ? "all" : experimental ? "experimental" : "safe"));
        sum.append("level   public APIs   already there   portable   blocked\n");
        int tp = 0, ts = 0, tb = 0;
        for (int l = from; l <= to; l++) {
            int total = present[l] + ported[l] + stopped[l];
            sum.append(String.format("api%-4d %11d %15d %10d %9d%n", l, total, present[l], ported[l], stopped[l]));
            tp += present[l]; ts += ported[l]; tb += stopped[l];
        }
        sum.append(String.format("total   %11d %15d %10d %9d%n", tp + ts + tb, tp, ts, tb));
        sum.append(String.format("classes pulled in from AOSP (incl. hidden helpers): %d%n", pulled));
        sum.append("root causes for blocked APIs:\n");
        causes.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(25)
                .forEach(e -> sum.append(String.format("  %6d  %s%n", e.getValue(), e.getKey())));
        System.out.print(sum);
        if (emitDir != null) emit(emitDir, apis, blocked);
        try (PrintWriter w = new PrintWriter(args[5])) {
            w.print(sum);
            w.println();
            lines.forEach(w::println);
        }
    }
}
