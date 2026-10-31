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
 * Reflection for a Starsector mod, without naming anything in {@code java.lang.reflect}.
 *
 * Obfuscated game code can only be reached by reflection, but the game's mod classloader checks
 * the name of every class it is asked to load. In 0.98a it refuses anything under {@code java.lang.reflect}
 *
 * Credits: 
 * This mimics the workaround in LunaLib's {@code lunalib.backend.util.ReflectionUtils}, made by Lukas04
 * and improved with help from Starficz.
 *
 */
public final class Refl {

    /** The value of {@code java.lang.reflect.Modifier.STATIC}, which this class cannot name. */
    private static final int STATIC_MODIFIER = 0x0008;

    private static final Class<?> FIELD_CLASS = bootstrapClass("java.lang.reflect.Field");
    private static final Class<?> METHOD_CLASS = bootstrapClass("java.lang.reflect.Method");
    private static final Class<?> CONSTRUCTOR_CLASS = bootstrapClass("java.lang.reflect.Constructor");

    private static final MethodHandle GET_DECLARED_FIELDS =
            virtualHandle(Class.class, "getDeclaredFields", arrayClassOf(FIELD_CLASS));
    private static final MethodHandle GET_DECLARED_METHODS =
            virtualHandle(Class.class, "getDeclaredMethods", arrayClassOf(METHOD_CLASS));
    private static final MethodHandle GET_DECLARED_CONSTRUCTORS =
            virtualHandle(Class.class, "getDeclaredConstructors", arrayClassOf(CONSTRUCTOR_CLASS));

    private static final MethodHandle FIELD_GET_NAME = virtualHandle(FIELD_CLASS, "getName", String.class);
    private static final MethodHandle FIELD_GET_TYPE = virtualHandle(FIELD_CLASS, "getType", Class.class);
    private static final MethodHandle FIELD_GET_MODIFIERS = virtualHandle(FIELD_CLASS, "getModifiers", int.class);
    private static final MethodHandle FIELD_SET_ACCESSIBLE =
            virtualHandle(FIELD_CLASS, "setAccessible", void.class, boolean.class);
    private static final MethodHandle FIELD_GET = virtualHandle(FIELD_CLASS, "get", Object.class, Object.class);
    private static final MethodHandle FIELD_SET =
            virtualHandle(FIELD_CLASS, "set", void.class, Object.class, Object.class);

    private static final MethodHandle METHOD_GET_NAME = virtualHandle(METHOD_CLASS, "getName", String.class);
    private static final MethodHandle METHOD_GET_PARAMETER_TYPES =
            virtualHandle(METHOD_CLASS, "getParameterTypes", Class[].class);
    private static final MethodHandle METHOD_GET_RETURN_TYPE = virtualHandle(METHOD_CLASS, "getReturnType", Class.class);
    private static final MethodHandle METHOD_GET_MODIFIERS = virtualHandle(METHOD_CLASS, "getModifiers", int.class);
    private static final MethodHandle METHOD_SET_ACCESSIBLE =
            virtualHandle(METHOD_CLASS, "setAccessible", void.class, boolean.class);
    // Method.invoke and Constructor.newInstance are varargs; fixed arity passes the argument array through as it is.
    private static final MethodHandle METHOD_INVOKE =
            virtualHandle(METHOD_CLASS, "invoke", Object.class, Object.class, Object[].class).asFixedArity();

    private static final MethodHandle CONSTRUCTOR_GET_PARAMETER_TYPES =
            virtualHandle(CONSTRUCTOR_CLASS, "getParameterTypes", Class[].class);
    private static final MethodHandle CONSTRUCTOR_SET_ACCESSIBLE =
            virtualHandle(CONSTRUCTOR_CLASS, "setAccessible", void.class, boolean.class);
    private static final MethodHandle CONSTRUCTOR_NEW_INSTANCE =
            virtualHandle(CONSTRUCTOR_CLASS, "newInstance", Object.class, Object[].class).asFixedArity();

    private static final Map<Class<?>, List<ReflectedField>> FIELD_CACHE = new HashMap<>();
    private static final Map<Class<?>, List<ReflectedMethod>> METHOD_CACHE = new HashMap<>();

    private Refl() {
    }

    // ---------------------------------------------------------------- wrappers

    /** A field of some class, ready to read and write. {@code field} is really a {@code java.lang.reflect.Field}. */
    public static final class ReflectedField {
        private final Object field;
        public final String name;
        public final Class<?> type;
        public final boolean isStatic;
        /** The class that declares this field. */
        public final Class<?> owner;

        ReflectedField(Object field, Class<?> owner) throws Throwable {
            this.field = field;
            this.owner = owner;
            this.name = (String) FIELD_GET_NAME.invoke(field);
            this.type = (Class<?>) FIELD_GET_TYPE.invoke(field);
            this.isStatic = ((int) FIELD_GET_MODIFIERS.invoke(field) & STATIC_MODIFIER) != 0;
            trySetAccessible(FIELD_SET_ACCESSIBLE, field);
        }

        public Object get(Object instance) {
            try {
                return FIELD_GET.invoke(field, instance);
            } catch (Throwable failure) {
                throw wrapFailure("get " + this, failure);
            }
        }

        public void set(Object instance, Object value) {
            try {
                FIELD_SET.invoke(field, instance, value);
            } catch (Throwable failure) {
                throw wrapFailure("set " + this, failure);
            }
        }

        @Override
        public String toString() {
            return owner.getSimpleName() + "." + name + ":" + type.getSimpleName();
        }
    }

    /** A method of some class, ready to invoke. {@code method} is really a {@code java.lang.reflect.Method}. */
    public static final class ReflectedMethod {
        private final Object method;
        public final String name;
        public final Class<?>[] parameterTypes;
        public final Class<?> returnType;
        public final boolean isStatic;
        /** The class that declares this method. */
        public final Class<?> owner;

        ReflectedMethod(Object method, Class<?> owner) throws Throwable {
            this.method = method;
            this.owner = owner;
            this.name = (String) METHOD_GET_NAME.invoke(method);
            this.parameterTypes = (Class<?>[]) METHOD_GET_PARAMETER_TYPES.invoke(method);
            this.returnType = (Class<?>) METHOD_GET_RETURN_TYPE.invoke(method);
            this.isStatic = ((int) METHOD_GET_MODIFIERS.invoke(method) & STATIC_MODIFIER) != 0;
            trySetAccessible(METHOD_SET_ACCESSIBLE, method);
        }

        public Object invoke(Object instance, Object... arguments) {
            try {
                return METHOD_INVOKE.invoke(method, instance, arguments);
            } catch (Throwable failure) {
                throw wrapFailure("invoke " + this, unwrapInvocationFailure(failure));
            }
        }

        @Override
        public String toString() {
            StringBuilder description = new StringBuilder(owner.getSimpleName()).append('.').append(name).append('(');
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i > 0) description.append(',');
                description.append(parameterTypes[i].getSimpleName());
            }
            return description.append(')').toString();
        }
    }

    // ---------------------------------------------------------------- lookup

    /** Finds a class through this mod's own classloader, without initializing it. */
    public static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, Refl.class.getClassLoader());
        } catch (Throwable failure) {
            throw wrapFailure("class " + name, failure);
        }
    }

    /** Declared fields of the class and all its superclasses, subclass first. */
    public static synchronized List<ReflectedField> fields(Class<?> targetClass) {
        List<ReflectedField> cached = FIELD_CACHE.get(targetClass);
        if (cached != null) return cached;
        List<ReflectedField> result = new ArrayList<>();
        for (Class<?> declaringClass = targetClass; declaringClass != null && declaringClass != Object.class;
                declaringClass = declaringClass.getSuperclass()) {
            try {
                for (Object field : (Object[]) GET_DECLARED_FIELDS.invoke(declaringClass)) {
                    result.add(new ReflectedField(field, declaringClass));
                }
            } catch (Throwable failure) {
                throw wrapFailure("fields of " + declaringClass.getName(), failure);
            }
        }
        result = Collections.unmodifiableList(result);
        FIELD_CACHE.put(targetClass, result);
        return result;
    }

    /** Declared methods of the class and all its superclasses, subclass first. */
    public static synchronized List<ReflectedMethod> methods(Class<?> targetClass) {
        List<ReflectedMethod> cached = METHOD_CACHE.get(targetClass);
        if (cached != null) return cached;
        List<ReflectedMethod> result = new ArrayList<>();
        for (Class<?> declaringClass = targetClass; declaringClass != null && declaringClass != Object.class;
                declaringClass = declaringClass.getSuperclass()) {
            try {
                for (Object method : (Object[]) GET_DECLARED_METHODS.invoke(declaringClass)) {
                    result.add(new ReflectedMethod(method, declaringClass));
                }
            } catch (Throwable failure) {
                throw wrapFailure("methods of " + declaringClass.getName(), failure);
            }
        }
        result = Collections.unmodifiableList(result);
        METHOD_CACHE.put(targetClass, result);
        return result;
    }

    /** First field assignable to {@code type} that is static or not as {@code wantStatic} asks, or null. */
    public static ReflectedField fieldOfType(Class<?> targetClass, Class<?> type, boolean wantStatic) {
        for (ReflectedField field : fields(targetClass)) {
            if (field.isStatic == wantStatic && type.isAssignableFrom(field.type)) return field;
        }
        return null;
    }

    /** First field with this name, or null. */
    public static ReflectedField fieldNamed(Class<?> targetClass, String name) {
        for (ReflectedField field : fields(targetClass)) {
            if (field.name.equals(name)) return field;
        }
        return null;
    }

    /**
     * First method with this name whose parameter types exactly match {@code parameterTypes}. A null entry matches any
     * type, which finds an obfuscated class through an unobfuscated method that takes it.
     */
    public static ReflectedMethod method(Class<?> targetClass, String name, Class<?>... parameterTypes) {
        for (ReflectedMethod method : methods(targetClass)) {
            if (method.name.equals(name) && sameParameterTypes(method.parameterTypes, parameterTypes)) return method;
        }
        return null;
    }

    /** Like {@link #method}, but also matching the return type (obfuscated classes overload on return type alone). */
    public static ReflectedMethod method(Class<?> targetClass, Class<?> returnType, String name, Class<?>... parameterTypes) {
        for (ReflectedMethod method : methods(targetClass)) {
            if (method.name.equals(name) && method.returnType == returnType
                    && sameParameterTypes(method.parameterTypes, parameterTypes)) {
                return method;
            }
        }
        return null;
    }

    /** Invoke a no-arg (or exact-arity, boxed-compatible) method by name; for one-off diagnostics, not hot paths. */
    public static Object call(Object instance, String name, Object... arguments) {
        for (ReflectedMethod method : methods(instance.getClass())) {
            if (!method.isStatic && method.name.equals(name) && method.parameterTypes.length == arguments.length
                    && argumentsFit(method.parameterTypes, arguments)) {
                return method.invoke(instance, arguments);
            }
        }
        throw new IllegalStateException("no method " + name + "/" + arguments.length + " on " + instance.getClass().getName());
    }

    /** Creates an instance through the first constructor the arguments fit, private ones included. */
    public static Object newInstance(Class<?> targetClass, Object... arguments) {
        try {
            for (Object constructor : (Object[]) GET_DECLARED_CONSTRUCTORS.invoke(targetClass)) {
                Class<?>[] parameterTypes = (Class<?>[]) CONSTRUCTOR_GET_PARAMETER_TYPES.invoke(constructor);
                if (parameterTypes.length == arguments.length && argumentsFit(parameterTypes, arguments)) {
                    CONSTRUCTOR_SET_ACCESSIBLE.invoke(constructor, true);
                    return CONSTRUCTOR_NEW_INSTANCE.invoke(constructor, arguments);
                }
            }
        } catch (Throwable failure) {
            throw wrapFailure("new " + targetClass.getName(), unwrapInvocationFailure(failure));
        }
        throw new IllegalStateException("no constructor/" + arguments.length + " on " + targetClass.getName());
    }

    // ---------------------------------------------------------------- internals

    /** Exact match of {@code actual} against {@code wanted}, where a null entry in {@code wanted} matches any type. */
    private static boolean sameParameterTypes(Class<?>[] actual, Class<?>[] wanted) {
        if (actual.length != wanted.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (wanted[i] != null && actual[i] != wanted[i]) return false;
        }
        return true;
    }

    private static boolean argumentsFit(Class<?>[] parameterTypes, Object[] arguments) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object argument = arguments[i];
            Class<?> boxedParameterType = boxedType(parameterTypes[i]);
            if (argument == null) {
                if (parameterTypes[i].isPrimitive()) return false;
            } else if (!boxedParameterType.isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> boxedType(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == float.class) return Float.class;
        if (type == int.class) return Integer.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    /**
     * Reflective calls wrap the real failure in InvocationTargetException, so dig it out. The game does allow that class,
     * but matching on its name keeps this file free of references to java.lang.reflect.
     */
    private static Throwable unwrapInvocationFailure(Throwable failure) {
        if (failure.getClass().getName().equals("java.lang.reflect.InvocationTargetException") && failure.getCause() != null) {
            return failure.getCause();
        }
        return failure;
    }

    private static RuntimeException wrapFailure(String action, Throwable failure) {
        return new IllegalStateException("Refl: " + action + " failed: " + failure, failure);
    }

    /** JDK classes refuse setAccessible on Java 9+; those members stay usable if they are public. */
    private static void trySetAccessible(MethodHandle setAccessible, Object member) {
        try {
            setAccessible.invoke(member, true);
        } catch (Throwable ignored) {
        }
    }

    /** A class fetched from the bootstrap loader, which a null loader selects; see the class doc. */
    private static Class<?> bootstrapClass(String name) {
        try {
            return Class.forName(name, false, null);
        } catch (ClassNotFoundException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** The array class of {@code componentClass}, needed to describe methods that return arrays. */
    private static Class<?> arrayClassOf(Class<?> componentClass) {
        try {
            return Class.forName("[L" + componentClass.getName() + ";", false, componentClass.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /** A handle to an instance method of {@code owner}, which takes the instance as its first argument. */
    private static MethodHandle virtualHandle(Class<?> owner, String name, Class<?> returnType, Class<?>... parameterTypes) {
        try {
            return MethodHandles.lookup().findVirtual(owner, name, MethodType.methodType(returnType, parameterTypes));
        } catch (Throwable failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
