package backport.patcher;

import com.android.tools.smali.dexlib2.AccessFlags;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction11x;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction3rc;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds the small methods that get added to existing framework classes.
 * Each one only forwards its arguments to the backport implementation.
 *
 * Instance method:  T.foo(args)  ->  return Impl.foo(this, args)
 * Static method:    T.foo(args)  ->  return Impl.foo(args)
 * Constructor:      new T(args)  ->  this.<init>(first args); Impl.init(this, args)
 */
final class Bridge {
    private Bridge() {}

    /** Register width of one type ("J" and "D" take two registers). */
    static int width(String type) {
        return type.equals("J") || type.equals("D") ? 2 : 1;
    }

    /** Parameter types of a descriptor like "(ILjava/lang/String;)V". */
    static List<String> params(String desc) {
        List<String> out = new ArrayList<>();
        int i = 1;
        while (desc.charAt(i) != ')') {
            int start = i;
            while (desc.charAt(i) == '[') i++;
            if (desc.charAt(i) == 'L') i = desc.indexOf(';', i);
            i++;
            out.add(desc.substring(start, i));
        }
        return out;
    }

    static String returnType(String desc) {
        return desc.substring(desc.indexOf(')') + 1);
    }

    static int argWords(List<String> types) {
        int n = 0;
        for (String t : types) n += width(t);
        return n;
    }

    /** Instance or static forwarding method, added to `target`. */
    static ImmutableMethod forward(Injection inj) {
        boolean isStatic = inj.kind == Injection.Kind.STATIC;
        List<String> params = params(inj.desc);
        String ret = returnType(inj.desc);
        int words = argWords(params) + (isStatic ? 0 : 1);
        int regs = Math.max(words, width(ret.equals("V") ? "I" : ret));

        MutableMethodImplementation code = new MutableMethodImplementation(regs);
        // arguments sit in the last `words` registers; pass them on as one range
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, regs - words, words,
                new ImmutableMethodReference(inj.implClass, inj.implName,
                        params(inj.implDesc), returnType(inj.implDesc))));
        addReturn(code, ret);

        int access = AccessFlags.PUBLIC.getValue() | (isStatic ? AccessFlags.STATIC.getValue() : 0);
        return new ImmutableMethod(inj.target, inj.name, parameters(params), ret, access,
                Collections.emptySet(), Collections.emptySet(), code);
    }

    /** New constructor: call the existing delegate constructor, then the backport init. */
    static ImmutableMethod constructor(Injection inj) {
        List<String> params = params(inj.desc);
        List<String> delegate = params(inj.delegateDesc);
        int words = argWords(params) + 1;            // + this
        int delegateWords = argWords(delegate) + 1;
        int regs = words;
        int first = regs - words;                    // register holding `this`

        MutableMethodImplementation code = new MutableMethodImplementation(regs);
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, first, delegateWords,
                new ImmutableMethodReference(inj.target, "<init>", delegate, "V")));
        code.addInstruction(new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, first, words,
                new ImmutableMethodReference(inj.implClass, inj.implName,
                        params(inj.implDesc), "V")));
        code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));

        int access = AccessFlags.PUBLIC.getValue() | AccessFlags.CONSTRUCTOR.getValue();
        return new ImmutableMethod(inj.target, "<init>", parameters(params), "V", access,
                Collections.emptySet(), Collections.emptySet(), code);
    }

    private static void addReturn(MutableMethodImplementation code, String ret) {
        switch (ret.charAt(0)) {
            case 'V':
                code.addInstruction(new BuilderInstruction10x(Opcode.RETURN_VOID));
                return;
            case 'J':
            case 'D':
                code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_WIDE, 0));
                code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_WIDE, 0));
                return;
            case 'L':
            case '[':
                code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0));
                code.addInstruction(new BuilderInstruction11x(Opcode.RETURN_OBJECT, 0));
                return;
            default:
                code.addInstruction(new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
                code.addInstruction(new BuilderInstruction11x(Opcode.RETURN, 0));
        }
    }

    private static List<ImmutableMethodParameter> parameters(List<String> types) {
        List<ImmutableMethodParameter> out = new ArrayList<>();
        for (String t : types) out.add(new ImmutableMethodParameter(t, null, null));
        return out;
    }
}
