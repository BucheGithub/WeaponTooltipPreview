package wtp.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection without referencing java.lang.reflect: Starsector's mod classloader refuses those classes, so
 * Field/Method/Constructor objects are fetched from the bootstrap loader and driven through MethodHandles.
 */
public final class Refl {

    private static final int STATIC = 0x0008;

    private static final Class<?> FIELD = bootClass("java.lang.reflect.Field");
    private static final Class<?> METHOD = bootClass("java.lang.reflect.Method");
    private static final Class<?> CTOR = bootClass("java.lang.reflect.Constructor");

    private static final MethodHandle DECLARED_FIELDS = virtual(Class.class, "getDeclaredFields", arrayOf(FIELD));
    private static final MethodHandle DECLARED_METHODS = virtual(Class.class, "getDeclaredMethods", arrayOf(METHOD));
    private static final MethodHandle DECLARED_CTORS = virtual(Class.class, "getDeclaredConstructors", arrayOf(CTOR));

    private static final MethodHandle F_NAME = virtual(FIELD, "getName", String.class);
    private static final MethodHandle F_TYPE = virtual(FIELD, "getType", Class.class);
    private static final MethodHandle F_MODS = virtual(FIELD, "getModifiers", int.class);
    private static final MethodHandle F_ACCESSIBLE = virtual(FIELD, "setAccessible", void.class, boolean.class);
    private static final MethodHandle F_GET = virtual(FIELD, "get", Object.class, Object.class);
    private static final MethodHandle F_SET = virtual(FIELD, "set", void.class, Object.class, Object.class);

    private static final MethodHandle M_NAME = virtual(METHOD, "getName", String.class);
    private static final MethodHandle M_PARAMS = virtual(METHOD, "getParameterTypes", Class[].class);
    private static final MethodHandle M_RETURN = virtual(METHOD, "getReturnType", Class.class);
    private static final MethodHandle M_MODS = virtual(METHOD, "getModifiers", int.class);
    private static final MethodHandle M_ACCESSIBLE = virtual(METHOD, "setAccessible", void.class, boolean.class);
    private static final MethodHandle M_INVOKE = virtual(METHOD, "invoke", Object.class, Object.class, Object[].class).asFixedArity();

    private static final MethodHandle C_PARAMS = virtual(CTOR, "getParameterTypes", Class[].class);
    private static final MethodHandle C_ACCESSIBLE = virtual(CTOR, "setAccessible", void.class, boolean.class);
    private static final MethodHandle C_NEW = virtual(CTOR, "newInstance", Object.class, Object[].class).asFixedArity();

    private static final Map<Class<?>, List<RField>> FIELD_CACHE = new HashMap<>();
    private static final Map<Class<?>, List<RMethod>> METHOD_CACHE = new HashMap<>();

    private Refl() {
    }

    // ---------------------------------------------------------------- wrappers

    public static final class RField {
        private final Object field;
        public final String name;
        public final Class<?> type;
        public final boolean isStatic;
        public final Class<?> owner;

        RField(Object field, Class<?> owner) throws Throwable {
            this.field = field;
            this.owner = owner;
            this.name = (String) F_NAME.invoke(field);
            this.type = (Class<?>) F_TYPE.invoke(field);
            this.isStatic = ((int) F_MODS.invoke(field) & STATIC) != 0;
            trySetAccessible(F_ACCESSIBLE, field);
        }

        public Object get(Object instance) {
            try {
                return F_GET.invoke(field, instance);
            } catch (Throwable t) {
                throw wrap("get " + this, t);
            }
        }

        public void set(Object instance, Object value) {
            try {
                F_SET.invoke(field, instance, value);
            } catch (Throwable t) {
                throw wrap("set " + this, t);
            }
        }

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + name + ":" + type.getSimpleName();
        }
    }

    public static final class RMethod {
        private final Object method;
        public final String name;
        public final Class<?>[] params;
        public final Class<?> returnType;
        public final boolean isStatic;
        public final Class<?> owner;

        RMethod(Object method, Class<?> owner) throws Throwable {
            this.method = method;
            this.owner = owner;
            this.name = (String) M_NAME.invoke(method);
            this.params = (Class<?>[]) M_PARAMS.invoke(method);
            this.returnType = (Class<?>) M_RETURN.invoke(method);
            this.isStatic = ((int) M_MODS.invoke(method) & STATIC) != 0;
            trySetAccessible(M_ACCESSIBLE, method);
        }

        public Object invoke(Object instance, Object... args) {
            try {
                return M_INVOKE.invoke(method, instance, args);
            } catch (Throwable t) {
                throw wrap("invoke " + this, unwrapInvocation(t));
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(owner.getSimpleName()).append('.').append(name).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(params[i].getSimpleName());
            }
            return sb.append(')').toString();
        }
    }

    // ---------------------------------------------------------------- lookup

    public static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, Refl.class.getClassLoader());
        } catch (Throwable t) {
            throw wrap("class " + name, t);
        }
    }

    /** Declared fields of the class and all its superclasses, subclass first. */
    public static synchronized List<RField> fields(Class<?> cls) {
        List<RField> cached = FIELD_CACHE.get(cls);
        if (cached != null) return cached;
        List<RField> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Object f : (Object[]) DECLARED_FIELDS.invoke(c)) out.add(new RField(f, c));
            } catch (Throwable t) {
                throw wrap("fields of " + c.getName(), t);
            }
        }
        out = Collections.unmodifiableList(out);
        FIELD_CACHE.put(cls, out);
        return out;
    }

    /** Declared methods of the class and all its superclasses, subclass first. */
    public static synchronized List<RMethod> methods(Class<?> cls) {
        List<RMethod> cached = METHOD_CACHE.get(cls);
        if (cached != null) return cached;
        List<RMethod> out = new ArrayList<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                for (Object m : (Object[]) DECLARED_METHODS.invoke(c)) out.add(new RMethod(m, c));
            } catch (Throwable t) {
                throw wrap("methods of " + c.getName(), t);
            }
        }
        out = Collections.unmodifiableList(out);
        METHOD_CACHE.put(cls, out);
        return out;
    }

    public static RField fieldOfType(Class<?> cls, Class<?> type, boolean wantStatic) {
        for (RField f : fields(cls)) {
            if (f.isStatic == wantStatic && type.isAssignableFrom(f.type)) return f;
        }
        return null;
    }

    public static RField fieldNamed(Class<?> cls, String name) {
        for (RField f : fields(cls)) {
            if (f.name.equals(name)) return f;
        }
        return null;
    }

    /** First method with this name whose parameter types exactly match {@code params}. */
    public static RMethod method(Class<?> cls, String name, Class<?>... params) {
        for (RMethod m : methods(cls)) {
            if (m.name.equals(name) && sameParams(m.params, params)) return m;
        }
        return null;
    }

    /** Like {@link #method}, but also matching the return type (obfuscated classes overload on return type alone). */
    public static RMethod method(Class<?> cls, Class<?> returnType, String name, Class<?>... params) {
        for (RMethod m : methods(cls)) {
            if (m.name.equals(name) && m.returnType == returnType && sameParams(m.params, params)) return m;
        }
        return null;
    }

    public static RMethod requireMethod(Class<?> cls, String name, Class<?>... params) {
        RMethod m = method(cls, name, params);
        if (m == null) throw new IllegalStateException("missing method " + cls.getName() + "." + name);
        return m;
    }

    /** Invoke a no-arg (or exact-arity, boxed-compatible) method by name; for one-off diagnostics, not hot paths. */
    public static Object call(Object instance, String name, Object... args) {
        for (RMethod m : methods(instance.getClass())) {
            if (!m.isStatic && m.name.equals(name) && m.params.length == args.length && argsFit(m.params, args)) {
                return m.invoke(instance, args);
            }
        }
        throw new IllegalStateException("no method " + name + "/" + args.length + " on " + instance.getClass().getName());
    }

    public static Object callStatic(Class<?> cls, String name, Object... args) {
        for (RMethod m : methods(cls)) {
            if (m.isStatic && m.name.equals(name) && m.params.length == args.length && argsFit(m.params, args)) {
                return m.invoke(null, args);
            }
        }
        throw new IllegalStateException("no static method " + name + "/" + args.length + " on " + cls.getName());
    }

    public static boolean hasMethod(Object instance, String name, int paramCount) {
        for (RMethod m : methods(instance.getClass())) {
            if (m.name.equals(name) && m.params.length == paramCount) return true;
        }
        return false;
    }

    public static Object newInstance(Class<?> cls, Object... args) {
        try {
            for (Object c : (Object[]) DECLARED_CTORS.invoke(cls)) {
                Class<?>[] params = (Class<?>[]) C_PARAMS.invoke(c);
                if (params.length == args.length && argsFit(params, args)) {
                    C_ACCESSIBLE.invoke(c, true);
                    return C_NEW.invoke(c, args);
                }
            }
        } catch (Throwable t) {
            throw wrap("new " + cls.getName(), unwrapInvocation(t));
        }
        throw new IllegalStateException("no constructor/" + args.length + " on " + cls.getName());
    }

    /** True if any superclass of {@code o}'s class has exactly this name. */
    public static boolean isA(Object o, String className) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            if (c.getName().equals(className)) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- internals

    private static boolean sameParams(Class<?>[] a, Class<?>[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) return false;
        }
        return true;
    }

    private static boolean argsFit(Class<?>[] params, Object[] args) {
        for (int i = 0; i < params.length; i++) {
            Object a = args[i];
            Class<?> p = box(params[i]);
            if (a == null) {
                if (params[i].isPrimitive()) return false;
            } else if (!p.isInstance(a)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> box(Class<?> c) {
        if (!c.isPrimitive()) return c;
        if (c == float.class) return Float.class;
        if (c == int.class) return Integer.class;
        if (c == boolean.class) return Boolean.class;
        if (c == double.class) return Double.class;
        if (c == long.class) return Long.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return Void.class;
    }

    /** Reflective invoke wraps the real failure; dig it out without naming InvocationTargetException. */
    private static Throwable unwrapInvocation(Throwable t) {
        if (t.getClass().getName().equals("java.lang.reflect.InvocationTargetException") && t.getCause() != null) {
            return t.getCause();
        }
        return t;
    }

    private static RuntimeException wrap(String what, Throwable t) {
        return new IllegalStateException("Refl: " + what + " failed: " + t, t);
    }

    /** JDK classes refuse setAccessible on Java 9+; those members stay usable if they are public. */
    private static void trySetAccessible(MethodHandle setAccessible, Object member) {
        try {
            setAccessible.invoke(member, true);
        } catch (Throwable ignored) {
        }
    }

    private static Class<?> bootClass(String name) {
        try {
            return Class.forName(name, false, null);
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Class<?> arrayOf(Class<?> component) {
        try {
            return Class.forName("[L" + component.getName() + ";", false, component.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle virtual(Class<?> owner, String name, Class<?> ret, Class<?>... params) {
        try {
            return MethodHandles.lookup().findVirtual(owner, name, MethodType.methodType(ret, params));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
}
