import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.iface.Field;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.iface.MultiDexContainer;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import org.objectweb.asm.ClassWriter;

/**
 * Turns a phone's framework code (dex) into a reference jar for the porter:
 * one header-only .class per class (name, flags, superclass, interfaces,
 * fields and methods with their descriptors, no code).
 *
 *   java -cp dexlib2.jar:guava.jar:asm.jar tools/RefJar.java out.jar INPUT...
 *
 * INPUT: .dex files, or .jar/.apk files that contain classes*.dex. A jar
 * from a pre-optimised ROM has no dex inside; extract its .vdex first
 * (build.py does that with vdexExtractor). If a class appears in several
 * inputs, the first one wins, like on the phone's boot classpath.
 */
public class RefJar {
    /** dex-only access bits (constructor, declared-synchronized) aren't valid in class files */
    static final int JAVA_FLAGS = 0xFFFF;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: RefJar out.jar INPUT.dex|INPUT.jar ...");
            System.exit(2);
        }
        Map<String, ClassDef> classes = new LinkedHashMap<>();
        int dexCount = 0;
        for (int i = 1; i < args.length; i++) {
            File f = new File(args[i]);
            List<DexFile> dexes = new ArrayList<>();
            if (f.getName().endsWith(".dex")) {
                dexes.add(DexFileFactory.loadDexFile(f, Opcodes.getDefault()));
            } else {
                MultiDexContainer<? extends DexFile> c = DexFileFactory.loadDexContainer(f, Opcodes.getDefault());
                for (String entry : c.getDexEntryNames()) dexes.add(c.getEntry(entry).getDexFile());
            }
            if (dexes.isEmpty()) {
                System.err.println("error: " + f + " contains no dex code (pre-optimised? extract its .vdex)");
                System.exit(1);
            }
            for (DexFile d : dexes) {
                dexCount++;
                for (ClassDef c : d.getClasses()) classes.putIfAbsent(c.getType(), c);
            }
        }
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(args[0]))) {
            for (ClassDef c : classes.values()) {
                String name = internal(c.getType());
                ClassWriter w = new ClassWriter(0);
                List<String> ifaces = new ArrayList<>();
                for (String t : c.getInterfaces()) ifaces.add(internal(t));
                w.visit(org.objectweb.asm.Opcodes.V1_8, c.getAccessFlags() & JAVA_FLAGS, name, null,
                        c.getSuperclass() == null ? null : internal(c.getSuperclass()), ifaces.toArray(new String[0]));
                for (Field f : c.getFields()) {
                    w.visitField(f.getAccessFlags() & JAVA_FLAGS, f.getName(), f.getType(), null, null).visitEnd();
                }
                for (Method m : c.getMethods()) {
                    StringBuilder desc = new StringBuilder("(");
                    for (CharSequence p : m.getParameterTypes()) desc.append(p);
                    desc.append(')').append(m.getReturnType());
                    w.visitMethod(m.getAccessFlags() & JAVA_FLAGS, m.getName(), desc.toString(), null, null).visitEnd();
                }
                w.visitEnd();
                out.putNextEntry(new ZipEntry(name + ".class"));
                out.write(w.toByteArray());
                out.closeEntry();
            }
        }
        System.out.printf("reference: %d classes from %d dex files -> %s%n", classes.size(), dexCount, args[0]);
    }

    /** "Landroid/view/View;" -> "android/view/View" */
    static String internal(String type) {
        return type.substring(1, type.length() - 1);
    }
}
