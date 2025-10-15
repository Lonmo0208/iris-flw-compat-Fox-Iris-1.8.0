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
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

public class IrisInstancingPrograms extends AtomicReferenceCounted {
    private static final Logger LOGGER = Logger.getLogger(IrisInstancingPrograms.class.getName());
    private static final List<String> EXTENSIONS = getExtensions(GlCompat.MAX_GLSL_VERSION);
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();

    private static volatile ShaderSources lastSources = null;
    private static volatile List<SourceComponent> lastVertexComponents = null;
    private static volatile List<SourceComponent> lastFragmentComponents = null;

    @Nullable
    private static volatile IrisInstancingPrograms instance;

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

        LOCK.readLock().lock();
        try {
            boolean sourcesChanged = !Objects.equals(sources, lastSources);
            boolean componentsChanged = false;
            
            if (!sourcesChanged) {
                componentsChanged = !Objects.equals(vertexComponents, lastVertexComponents) || 
                                  !Objects.equals(fragmentComponents, lastFragmentComponents);
            }
            
            if (!sourcesChanged && !componentsChanged && instance != null) {
                return;
            }
        } finally {
            LOCK.readLock().unlock();
        }

        LOCK.writeLock().lock();
        try {
            boolean sourcesChanged = !Objects.equals(sources, lastSources);
            boolean componentsChanged = !Objects.equals(vertexComponents, lastVertexComponents) || 
                                      !Objects.equals(fragmentComponents, lastFragmentComponents);
            
            if (!sourcesChanged && !componentsChanged && instance != null) {
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
        } finally {
            LOCK.writeLock().unlock();
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
        LOCK.readLock().lock();
        try {
            return instance != null;
        } finally {
            LOCK.readLock().unlock();
        }
    }

    public static void kill() {
        LOCK.writeLock().lock();
        try {
            lastSources = null;
            lastVertexComponents = null;
            lastFragmentComponents = null;
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
        pipeline.delete();
        oitPrograms.delete();
    }
}
