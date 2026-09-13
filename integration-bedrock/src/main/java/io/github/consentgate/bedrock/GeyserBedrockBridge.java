package io.github.consentgate.bedrock;

import io.github.consentgate.presentation.SafeTextFormatter;

import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.connection.GeyserConnection;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public final class GeyserBedrockBridge {
    private static final String FORM = "org.geysermc.cumulus.form.Form";
    private static final String FACTORY = "io.github.consentgate.bedrock.GeyserFormFactory";
    private static final ClassValue<Method> FACTORIES = new ClassValue<>() {
        @Override protected Method computeValue(Class<?> form) {
            try {
                return new FormLoader(form.getClassLoader()).loadClass(FACTORY).getMethod("create",
                        Predicate.class, Runnable.class, SafeTextFormatter.class, String.class, Function.class,
                        BooleanSupplier.class, Runnable.class, Consumer.class);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("Could not initialize Geyser's form renderer", ex);
            }
        }
    };

    private GeyserBedrockBridge() { }

    public static BedrockView open(UUID player, SafeTextFormatter formatter, String buttonColor, Function<String, String> messages,
                            BooleanSupplier active, Runnable leave, Consumer<RuntimeException> failure) {
        var api = GeyserApi.api();
        if (api == null) throw new IllegalStateException("Geyser is not initialized");
        var connection = api.connectionByUuid(player);
        if (connection == null) return null;
        return forConnection(GeyserConnection.class, connection, formatter, buttonColor, messages, active, leave, failure);
    }

    static BedrockView forConnection(Class<?> api, Object connection, SafeTextFormatter formatter, String buttonColor,
                                     Function<String, String> messages, BooleanSupplier active, Runnable leave,
                                     Consumer<RuntimeException> failure) {
        try {
            var send = Arrays.stream(api.getMethods()).filter(method -> method.getName().equals("sendForm")
                    && method.getParameterCount() == 1 && method.getParameterTypes()[0].getName().equals(FORM))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Geyser's form API is unavailable"));
            var close = api.getMethod("closeForm");
            Predicate<Object> sender = form -> Boolean.TRUE.equals(invoke(send, connection, form));
            Runnable closer = () -> invoke(close, connection);
            return (BedrockView) invoke(FACTORIES.get(send.getParameterTypes()[0]), null,
                    sender, closer, formatter, buttonColor, messages, active, leave, failure);
        } catch (NoSuchMethodException | LinkageError ex) {
            throw new IllegalStateException("Could not bind Geyser's form API", ex);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException failure) throw failure;
            if (ex.getCause() instanceof Error failure && !(failure instanceof LinkageError)) throw failure;
            throw new IllegalStateException("Geyser form operation failed", ex.getCause());
        } catch (ReflectiveOperationException | LinkageError ex) {
            throw new IllegalStateException("Geyser form operation failed", ex);
        }
    }

    /** Geyser and Floodgate may expose separate Cumulus copies through the server's plugin lookup. */
    private static final class FormLoader extends ClassLoader {
        private final ClassLoader provider;

        FormLoader(ClassLoader provider) {
            super(GeyserBedrockBridge.class.getClassLoader());
            this.provider = provider;
        }

        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.startsWith("org.geysermc.cumulus.")) loaded = provider.loadClass(name);
                    else if (owned(name)) loaded = findClass(name);
                    else loaded = super.loadClass(name, false);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }

        private static boolean owned(String name) {
            return name.equals(FACTORY) || name.startsWith(FACTORY + "$")
                    || name.equals("io.github.consentgate.bedrock.NativeBedrockForms")
                    || name.startsWith("io.github.consentgate.bedrock.NativeBedrockForms$")
                    || name.equals("io.github.consentgate.bedrock.BedrockText");
        }

        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (input == null) throw new ClassNotFoundException(name);
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException ex) { throw new ClassNotFoundException(name, ex); }
        }
    }
}
