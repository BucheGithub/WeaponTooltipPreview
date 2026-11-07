package weapontooltippreview.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection for a Starsector mod, without naming anything in java.reflect.
 *
 * Obfuscated game code can only be reached by reflection, but the game's mod classloader checks
 * the name of every class it is asked to load. In 0.98a it refuses anything under java.reflect.
 *
 * Credits:
 * This mimics the workaround in LunaLib's ReflectionUtils}, made by Lukas04 and improved with help from Starficz.
 */
public final class Refl {

    private static final int STATIC_MODIFIER = 0x0008;

    private static final Class<?> FIELD_CLASS = bootstrapClass("java.lang.reflect.Field");
    private static final Class<?> METHOD_CLASS = bootstrapClass("java.lang.reflect.Method");
    private static final Class<?> CONSTRUCTOR_CLASS = bootstrapClass("java.lang.reflect.Constructor");

    private static final MethodHandle GET_DECLARED_FIELDS =
            virtualHandle(Class.class, "getDeclaredFields", arrayClassOf(FIELD_CLASS));
    private static final MethodHandle GET_DECLARED_METHODS =
            virtualHandle(Class.class, "getDeclaredMethods", arrayClassOf(METHOD_CLASS));
    private static final MethodHandle GET_DECLARED_CONSTRUCTOR =
            virtualHandle(Class.class, "getDeclaredConstructor", CONSTRUCTOR_CLASS, Class[].class).asFixedArity();

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
    private static final MethodHandle METHOD_SET_ACCESSIBLE =
            virtualHandle(METHOD_CLASS, "setAccessible", void.class, boolean.class);
    private static final MethodHandle METHOD_INVOKE =
            virtualHandle(METHOD_CLASS, "invoke", Object.class, Object.class, Object[].class).asFixedArity();

    private static final MethodHandle CONSTRUCTOR_SET_ACCESSIBLE =
            virtualHandle(CONSTRUCTOR_CLASS, "setAccessible", void.class, boolean.class);
    private static final MethodHandle CONSTRUCTOR_NEW_INSTANCE =
            virtualHandle(CONSTRUCTOR_CLASS, "newInstance", Object.class, Object[].class).asFixedArity();

    private static final Map<Class<?>, List<ReflectedField>> FIELD_CACHE = new HashMap<>();
    private static final Map<Class<?>, List<ReflectedMethod>> METHOD_CACHE = new HashMap<>();

    private Refl() {
    }

    /**
    * Wrappers
    */

    public static final class ReflectedField {
        private final Object field;
        public final String name;
        public final Class<?> type;
        public final boolean isStatic;
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
            return owner.getName() + "." + name;
        }
    }

    public static final class ReflectedMethod {
        private final Object method;
        public final String name;
        public final Class<?>[] parameterTypes;
        public final Class<?> owner;

        ReflectedMethod(Object method, Class<?> owner) throws Throwable {
            this.method = method;
            this.owner = owner;
            this.name = (String) METHOD_GET_NAME.invoke(method);
            this.parameterTypes = (Class<?>[]) METHOD_GET_PARAMETER_TYPES.invoke(method);
            trySetAccessible(METHOD_SET_ACCESSIBLE, method);
        }

        public Object invoke(Object instance, Object... arguments) {
            try {
                return METHOD_INVOKE.invoke(method, instance, arguments);
            } catch (Throwable failure) {
                throw wrapFailure("invoke " + this, failure);
            }
        }

        @Override
        public String toString() {
            return owner.getName() + "." + name;
        }
    }

    /**
    * Lookup
    */
    
    /** Finds a class through this mod's own classloader, without initializing it. */
    public static Class<?> classForName(String name) {
        try {
            return Class.forName(name, false, Refl.class.getClassLoader());
        } catch (Throwable failure) {
            throw wrapFailure("class " + name, failure);
        }
    }

    /** Declared fields of the class and all its superclasses, subclass first. */
    public static List<ReflectedField> fields(Class<?> targetClass) {
        return FIELD_CACHE.computeIfAbsent(targetClass,
                key -> declaredMembers(key, GET_DECLARED_FIELDS, ReflectedField::new));
    }

    /** Declared methods of the class and all its superclasses, subclass first. */
    private static List<ReflectedMethod> methods(Class<?> targetClass) {
        return METHOD_CACHE.computeIfAbsent(targetClass,
                key -> declaredMembers(key, GET_DECLARED_METHODS, ReflectedMethod::new));
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

    /** First method with this name whose parameter types exactly match {@code parameterTypes}. */
    public static ReflectedMethod method(Class<?> targetClass, String name, Class<?>... parameterTypes) {
        for (ReflectedMethod method : methods(targetClass)) {
            if (method.name.equals(name) && sameParameterTypes(method.parameterTypes, parameterTypes)) return method;
        }
        return null;
    }

    /** Invokes a no-argument method by name. */
    public static Object call(Object instance, String name) {
        ReflectedMethod method = method(instance.getClass(), name);
        if (method == null) throw new IllegalStateException("no method " + name + " on " + instance.getClass().getName());
        return method.invoke(instance);
    }

    /** Creates an instance through the no-argument constructor, private or not. */
    public static Object newInstance(Class<?> targetClass) {
        try {
            Object constructor = GET_DECLARED_CONSTRUCTOR.invoke(targetClass, new Class<?>[0]);
            CONSTRUCTOR_SET_ACCESSIBLE.invoke(constructor, true);
            return CONSTRUCTOR_NEW_INSTANCE.invoke(constructor, new Object[0]);
        } catch (Throwable failure) {
            throw wrapFailure("new " + targetClass.getName(), failure);
        }
    }

    /**
    * Internal
    */
    private interface MemberWrapper<T> {
        T wrap(Object member, Class<?> owner) throws Throwable;
    }

    private static <T> List<T> declaredMembers(Class<?> targetClass, MethodHandle getDeclared, MemberWrapper<T> wrapper) {
        List<T> result = new ArrayList<>();
        for (Class<?> declaringClass = targetClass; declaringClass != null && declaringClass != Object.class;
                declaringClass = declaringClass.getSuperclass()) {
            try {
                for (Object member : (Object[]) getDeclared.invoke(declaringClass)) {
                    result.add(wrapper.wrap(member, declaringClass));
                }
            } catch (Throwable failure) {
                throw wrapFailure("members of " + declaringClass.getName(), failure);
            }
        }
        return result;
    }

    /** Exact match of {@code actual} against {@code wanted}, where a null entry in {@code wanted} matches any type. */
    private static boolean sameParameterTypes(Class<?>[] actual, Class<?>[] wanted) {
        if (actual.length != wanted.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (wanted[i] != null && actual[i] != wanted[i]) return false;
        }
        return true;
    }

    /** Reflective calls hide the real failure as the cause of their own exception. */
    private static RuntimeException wrapFailure(String action, Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        return new IllegalStateException("Refl: " + action + " failed: " + cause, cause);
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
