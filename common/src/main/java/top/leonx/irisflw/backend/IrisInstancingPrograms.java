package top.leonx.irisflw.backend;

import com.google.common.collect.ImmutableList;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.backend.compile.*;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.backend.glsl.GlslVersion;
import dev.engine_room.flywheel.backend.glsl.ShaderSources;
import dev.engine_room.flywheel.backend.glsl.SourceComponent;
import dev.engine_room.flywheel.backend.util.AtomicReferenceCounted;
import org.jetbrains.annotations.Nullable;
import top.leonx.irisflw.flywheel.IrisFlwCompatGlProgramBase;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IrisInstancingPrograms extends AtomicReferenceCounted {
    private static final Logger LOGGER = Logger.getLogger(IrisInstancingPrograms.class.getName());
    private static final List<String> EXTENSIONS = getExtensions(GlCompat.MAX_GLSL_VERSION);
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final ExecutorService PRELOAD_SERVICE = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Iris-Programs-Preloader");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile ShaderSources lastSources = null;
    private static volatile List<SourceComponent> lastVertexComponents = null;
    private static volatile List<SourceComponent> lastFragmentComponents = null;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    @Nullable
    private static volatile IrisInstancingPrograms instance;
    private static final CopyOnWriteArrayList<Runnable> INITIALIZATION_CALLBACKS = new CopyOnWriteArrayList<>();

    private final IrisPipelineCompiler pipeline;
    private final OitPrograms oitPrograms;

    private IrisInstancingPrograms(IrisPipelineCompiler pipeline, OitPrograms oitPrograms) {
        this.pipeline = pipeline;
        this.oitPrograms = oitPrograms;
    }

    private static List<String> getExtensions(GlslVersion glslVersion) {
        var extensions = ImmutableList.<String>builder();
        if (glslVersion != null && glslVersion.compareTo(GlslVersion.V330) < 0) {
            extensions.add("GL_ARB_shader_bit_encoding");
        }
        return extensions.build();
    }

    public static void reload(ShaderSources sources, List<SourceComponent> vertexComponents, List<SourceComponent> fragmentComponents) {
        if (!GlCompat.SUPPORTS_INSTANCING) {
            return;
        }

        boolean initialCheckPass;
        LOCK.readLock().lock();
        try {
            initialCheckPass = instance == null || 
                              !Objects.equals(sources, lastSources) || 
                              !Objects.equals(vertexComponents, lastVertexComponents) || 
                              !Objects.equals(fragmentComponents, lastFragmentComponents);
        } finally {
            LOCK.readLock().unlock();
        }

        if (!initialCheckPass) {
            return;
        }

        LOCK.writeLock().lock();
        try {
            boolean reloadNeeded = instance == null || 
                                  !Objects.equals(sources, lastSources) || 
                                  !Objects.equals(vertexComponents, lastVertexComponents) || 
                                  !Objects.equals(fragmentComponents, lastFragmentComponents);
            
            if (!reloadNeeded) {
                return;
            }

            lastSources = sources;
            lastVertexComponents = vertexComponents;
            lastFragmentComponents = fragmentComponents;

            try {
                var pipelineCompiler = IrisPipelineCompiler.create(sources, IrisFlwPipelines.IRIS_INSTANCING, 
                                                                 vertexComponents, fragmentComponents, EXTENSIONS);
                var fullscreen = OitPrograms.createFullscreenCompiler(sources);
                IrisInstancingPrograms newInstance = new IrisInstancingPrograms(pipelineCompiler, fullscreen);
                setInstance(newInstance);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to create new IrisInstancingPrograms instance", e);
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void setInstance(@Nullable IrisInstancingPrograms newInstance) {
        LOCK.writeLock().lock();
        try {
            if (instance != null) {
                instance.release();
            }
            if (newInstance != null) {
                newInstance.acquire();
            }
            instance = newInstance;
            if (newInstance != null && !INITIALIZED.getAndSet(true)) {
                PRELOAD_SERVICE.submit(() -> {
                    for (Runnable callback : INITIALIZATION_CALLBACKS) {
                        try {
                            callback.run();
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error executing initialization callback", e);
                        }
                    }
                    INITIALIZATION_CALLBACKS.clear();
                });
            }
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public static void registerInitializationCallback(Runnable callback) {
        if (INITIALIZED.get()) {
            try {
                callback.run();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error executing initialization callback", e);
            }
        } else {
            INITIALIZATION_CALLBACKS.add(callback);
        }
    }

    @Nullable
    public static IrisInstancingPrograms get() {
        LOCK.readLock().lock();
        try {
            return instance;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static boolean allLoaded() {
        return instance != null;
    }

    public static void preloadCommonPrograms() {
        PRELOAD_SERVICE.submit(() -> {
            if (!allLoaded()) {
                LOGGER.fine("Cannot preload programs - not initialized yet");
                return;
            }
            
            IrisInstancingPrograms currentInstance = get();
            if (currentInstance == null) return;
            
            try {
                LOGGER.fine("Preloading of common programs completed");
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error during program preloading", e);
            }
        });
    }

    public static void kill() {
        LOCK.writeLock().lock();
        try {
            lastSources = null;
            lastVertexComponents = null;
            lastFragmentComponents = null;
            INITIALIZED.set(false);
            INITIALIZATION_CALLBACKS.clear();
            setInstance(null);
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    public IrisFlwCompatGlProgramBase get(InstanceType<?> instanceType, ContextShader contextShader, Material material, PipelineCompiler.OitMode mode, boolean isShadow) {
        try {
            return (IrisFlwCompatGlProgramBase)pipeline.get(instanceType, contextShader, material, mode, isShadow);
        } catch (ClassCastException e) {
            LOGGER.log(Level.WARNING, "Failed to cast program to IrisFlwCompatGlProgramBase", e);
            return null;
        }
    }

    public OitPrograms oitPrograms() {
        return oitPrograms;
    }

    @Override
    protected void _delete() {
        try {
            if (pipeline != null) {
                pipeline.delete();
            }
            if (oitPrograms != null) {
                oitPrograms.delete();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during resource cleanup", e);
        }
    }

    public static void shutdown() {
        kill();
        PRELOAD_SERVICE.shutdown();
    }
}
