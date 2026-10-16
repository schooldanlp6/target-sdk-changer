package backport.patcher;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.BuilderInstruction;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction35c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Adds backported APIs to framework dex files.
 *
 *   Main <api level of phone> <out dir> <framework dex>... -- <level dir>...
 *
 * Each level dir (api27, api28, ...) holds classes.dex (backport code and
 * new classes) and inject.txt. Levels are applied in the order given.
 *
 * Output: the complete new dex set for the jar (classes.dex, classes2.dex,
 * ...), original classes first, new classes last. Nothing is installed
 * unless every step succeeds, so a failed run leaves the phone untouched.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int split = Arrays.asList(args).indexOf("--");
        if (args.length < 4 || split < 3) {
            System.err.println("usage: Main <phone api> <out dir> <framework dex>... -- <level dir>...");
            System.exit(2);
        }
        int api = Integer.parseInt(args[0]);
        File outDir = new File(args[1]);
        Opcodes opcodes = Opcodes.forApi(api);

        // class type -> (which framework dex, class)
        List<File> dexFiles = new ArrayList<>();
        List<Map<String, ClassDef>> classesPerDex = new ArrayList<>();
        Map<String, Integer> owner = new HashMap<>();
        for (int i = 2; i < split; i++) {
            File f = new File(args[i]);
            DexBackedDexFile dex = DexFileFactory.loadDexFile(f, opcodes);
            Map<String, ClassDef> classes = new LinkedHashMap<>();
            for (ClassDef c : dex.getClasses()) {
                classes.put(c.getType(), c);
                owner.putIfAbsent(c.getType(), dexFiles.size()); // first definition wins, like ART
            }
            dexFiles.add(f);
            classesPerDex.add(classes);
        }

        Map<String, List<Method>> added = new HashMap<>();   // target type -> new methods
        Map<String, List<Field>> addedFields = new HashMap<>(); // target type -> new fields (merged)
        Set<String> clinits = new HashSet<>(); // classes whose <clinit> must call $bp$clinit
        int merged = 0, clinitCalls = 0;
        List<ClassDef> newClasses = new ArrayList<>();
        int applied = 0, skipped = 0;

        for (int i = split + 1; i < args.length; i++) {
            Path level = Paths.get(args[i]);
            DexBackedDexFile code = DexFileFactory.loadDexFile(level.resolve("classes.dex").toFile(), opcodes);
            // merge.txt (from tools/Porter.java): partial classes whose members go into the phone's class
            Path clinitList = level.resolve("clinit.txt");
            if (java.nio.file.Files.exists(clinitList)) {
                for (String line : java.nio.file.Files.readAllLines(clinitList)) {
                    if (!line.trim().isEmpty()) clinits.add("L" + line.trim() + ";");
                }
            }
            Set<String> merge = new HashSet<>();
            Path mergeList = level.resolve("merge.txt");
            if (java.nio.file.Files.exists(mergeList)) {
                for (String line : java.nio.file.Files.readAllLines(mergeList)) {
                    if (!line.trim().isEmpty()) merge.add("L" + line.trim().split(" ")[0] + ";");
                }
            }
            for (ClassDef c : code.getClasses()) {
                Integer at = owner.get(c.getType());
                if (merge.contains(c.getType()) && (at == null || at < 0)) {
                    // partial class whose real class isn't in this jar: never add it as a new class
                    log("skip merge into %s: class not in this jar", c.getType());
                    skipped++;
                    continue;
                }
                if (merge.contains(c.getType()) && at != null && at >= 0) {
                    ClassDef target = classesPerDex.get(at).get(c.getType());
                    int kinds = AccessFlags.INTERFACE.getValue() | AccessFlags.ABSTRACT.getValue();
                    if (((target.getAccessFlags() ^ c.getAccessFlags()) & AccessFlags.INTERFACE.getValue()) != 0) {
                        log("skip merge into %s: class/interface mismatch", c.getType());
                        skipped++;
                        continue;
                    }
                    for (Method m : c.getMethods()) {
                        if (hasMethod(target, m.getName(), descriptor(m)) || hasAdded(added, c.getType(), m)) continue;
                        if ((m.getAccessFlags() & AccessFlags.ABSTRACT.getValue()) != 0
                                && (target.getAccessFlags() & kinds) == 0) {
                            log("skip %s.%s: abstract method on concrete class", c.getType(), m.getName());
                            skipped++;
                            continue;
                        }
                        added.computeIfAbsent(c.getType(), k -> new ArrayList<>()).add(ImmutableMethod.of(m));
                        merged++;
                    }
                    for (Field f : c.getFields()) {
                        if (hasField(target, f.getName()) || hasAddedField(addedFields, c.getType(), f)) continue;
                        addedFields.computeIfAbsent(c.getType(), k -> new ArrayList<>()).add(f);
                        merged++;
                    }
                    continue;
                }
                if (owner.containsKey(c.getType())) {
                    // already on the phone (ROM ships it, or an earlier level added it)
                    log("skip class %s: already present", c.getType());
                    skipped++;
                    continue;
                }
                newClasses.add(c);
                owner.put(c.getType(), -1);
            }
            Path list = level.resolve("inject.txt");
            List<Injection> injections = java.nio.file.Files.exists(list)
                    ? Injection.read(list) : Collections.<Injection>emptyList(); // "common": classes only
            for (Injection inj : injections) {
                if (inj.kind == Injection.Kind.CLASS) continue; // handled above
                if (inj.kind == Injection.Kind.FIELD) {
                    // TODO: static field injection (needs a <clinit> change)
                    log("skip %s: field injection not implemented yet", inj);
                    skipped++;
                    continue;
                }
                Integer where = owner.get(inj.target);
                if (where == null || where < 0) {
                    log("skip %s: target class not in framework", inj);
                    skipped++;
                    continue;
                }
                ClassDef target = classesPerDex.get(where).get(inj.target);
                if (hasMethod(target, inj.name, inj.desc) || hasAdded(added, inj)) {
                    log("skip %s: already present", inj);
                    skipped++;
                    continue;
                }
                ImmutableMethod m = inj.kind == Injection.Kind.CTOR ? Bridge.constructor(inj) : Bridge.forward(inj);
                added.computeIfAbsent(inj.target, k -> new ArrayList<>()).add(m);
                applied++;
            }
        }

        // write the complete new dex set: original classes in their original order
        // (patched where needed), then the new classes; a new dex file starts
        // whenever the current one would exceed the 64K reference limits
        outDir.mkdirs();
        DexSet out = new DexSet(outDir, opcodes);
        for (int d = 0; d < dexFiles.size(); d++) {
            for (ClassDef c : classesPerDex.get(d).values()) {
                if (owner.get(c.getType()) != d) continue; // shadowed duplicate: ART never loads it
                List<Method> extra = added.get(c.getType());
                List<Field> extraFields = addedFields.get(c.getType());
                ClassDef result = extra == null && extraFields == null ? c : withMembers(c, extra, extraFields);
                if (clinits.contains(c.getType()) && hasMethod(result, "$bp$clinit", "()V")) {
                    result = withClinitCall(result);
                    clinitCalls++;
                }
                out.add(result);
            }
        }
        for (ClassDef c : newClasses) out.add(c);
        log("ported static initialisers hooked: %d", clinitCalls);
        List<String> written = out.finish();
        log("applied %d, merged %d members, skipped %d, new classes %d, wrote %s",
                applied, merged, skipped, newClasses.size(), written);
    }

    static boolean hasMethod(ClassDef c, String name, String desc) {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && descriptor(m).equals(desc)) return true;
        }
        return false;
    }

    static boolean hasAdded(Map<String, List<Method>> added, Injection inj) {
        for (Method m : added.getOrDefault(inj.target, Collections.emptyList())) {
            if (m.getName().equals(inj.name) && descriptor(m).equals(inj.desc)) return true;
        }
        return false;
    }

    static String descriptor(Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (CharSequence p : m.getParameterTypes()) sb.append(p);
        return sb.append(')').append(m.getReturnType()).toString();
    }

    static ClassDef withMembers(ClassDef c, List<Method> extra, List<Field> extraFields) {
        List<Method> methods = new ArrayList<>();
        for (Method m : c.getMethods()) methods.add(m);
        if (extra != null) methods.addAll(extra);
        List<Field> fields = new ArrayList<>();
        for (Field f : c.getFields()) fields.add(f);
        if (extraFields != null) fields.addAll(extraFields);
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                c.getSourceFile(), c.getAnnotations(), fields, methods);
    }

    /**
     * Make the class's static initialiser call $bp$clinit() (the ported AOSP
     * one) at the end: every return-void in <clinit> is replaced by the call,
     * followed by a new return-void. Replacing keeps branch targets intact.
     */
    static ClassDef withClinitCall(ClassDef c) {
        ImmutableMethodReference hook = new ImmutableMethodReference(c.getType(), "$bp$clinit",
                Collections.<String>emptyList(), "V");
        List<Method> methods = new ArrayList<>();
        boolean found = false;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals("<clinit>") || m.getImplementation() == null) {
                methods.add(m);
                continue;
            }
            found = true;
            MutableMethodImplementation code = new MutableMethodImplementation(m.getImplementation());
            List<BuilderInstruction> insns = code.getInstructions();
            for (int i = insns.size() - 1; i >= 0; i--) {
                if (insns.get(i).getOpcode() == Opcode.RETURN_VOID) {
                    code.replaceInstruction(i, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, hook));
                    code.addInstruction(i + 1, new BuilderInstruction10x(Opcode.RETURN_VOID));
                }
            }
            methods.add(new ImmutableMethod(m.getDefiningClass(), m.getName(), m.getParameters(), m.getReturnType(),
                    m.getAccessFlags(), m.getAnnotations(), m.getHiddenApiRestrictions(), code));
        }
        if (!found) {
            MutableMethodImplementation code = new MutableMethodImplementation(0);
            code.addInstruction(new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, hook));
            code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
            methods.add(new ImmutableMethod(c.getType(), "<clinit>", Collections.emptyList(), "V",
                    AccessFlags.STATIC.getValue() | AccessFlags.CONSTRUCTOR.getValue(),
                    Collections.emptySet(), Collections.emptySet(), code));
        }
        return new ImmutableClassDef(c.getType(), c.getAccessFlags(), c.getSuperclass(), c.getInterfaces(),
                c.getSourceFile(), c.getAnnotations(), c.getFields(), methods);
    }

    static boolean hasAdded(Map<String, List<Method>> added, String type, Method m) {
        for (Method x : added.getOrDefault(type, Collections.emptyList())) {
            if (x.getName().equals(m.getName()) && descriptor(x).equals(descriptor(m))) return true;
        }
        return false;
    }

    static boolean hasField(ClassDef c, String name) {
        for (Field f : c.getFields()) if (f.getName().equals(name)) return true;
        return false;
    }

    static boolean hasAddedField(Map<String, List<Field>> added, String type, Field f) {
        for (Field x : added.getOrDefault(type, Collections.emptyList())) {
            if (x.getName().equals(f.getName())) return true;
        }
        return false;
    }

    /** Fills classes.dex, classes2.dex, ... starting a new file when one is full. */
    static final class DexSet {
        final File dir;
        final Opcodes opcodes;
        final List<String> written = new ArrayList<>();
        DexPool pool;
        int count;

        DexSet(File dir, Opcodes opcodes) {
            this.dir = dir;
            this.opcodes = opcodes;
            pool = new DexPool(opcodes);
        }

        void add(ClassDef c) throws java.io.IOException {
            pool.mark();
            pool.internClass(c);
            if (pool.hasOverflowed()) {
                pool.reset();
                if (count == 0) throw new IllegalStateException(c.getType() + " alone exceeds dex limits");
                flush();
                pool.internClass(c);
            }
            count++;
        }

        void flush() throws java.io.IOException {
            String name = written.isEmpty() ? "classes.dex" : "classes" + (written.size() + 1) + ".dex";
            pool.writeTo(new FileDataStore(new File(dir, name)));
            written.add(name + " (" + count + " classes)");
            pool = new DexPool(opcodes);
            count = 0;
        }

        List<String> finish() throws java.io.IOException {
            if (count > 0) flush();
            return written;
        }
    }

    static void log(String fmt, Object... args) {
        System.out.println("backport: " + String.format(fmt, args));
    }
}
