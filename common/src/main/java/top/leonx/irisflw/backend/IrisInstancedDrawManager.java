package top.leonx.irisflw.backend;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.compile.ContextShader;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.compile.PipelineCompiler;
import dev.engine_room.flywheel.backend.engine.*;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDraw;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedInstancer;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedLight;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.backend.gl.TextureBuffer;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelBakery;
import top.leonx.irisflw.flywheel.IrisFlwCompatGlProgramBase;
import top.leonx.irisflw.flywheel.RenderLayerEventStateManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class IrisInstancedDrawManager extends DrawManager<InstancedInstancer<?>> {
    private static final Comparator<InstancedDraw> DRAW_COMPARATOR = Comparator.comparing(InstancedDraw::bias)
            .thenComparing(InstancedDraw::indexOfMeshInModel)
            .thenComparing(InstancedDraw::material, MaterialRenderState.COMPARATOR);

    private final List<InstancedDraw> allDraws = new ArrayList<>();
    private boolean needSort = false;

    private final List<InstancedDraw> draws = new ArrayList<>();
    private final List<InstancedDraw> oitDraws = new ArrayList<>();

    private final IrisInstancingPrograms programs;
    /**
     * A map of vertex types to their mesh pools.
     */
    private final IrisMeshPool meshPool;
    private final GlVertexArray vao;
    private final TextureBuffer instanceTexture;
    private final InstancedLight light;

    private final OitFramebuffer oitFramebuffer;

    public IrisInstancedDrawManager(IrisInstancingPrograms programs) {
        programs.acquire();
        this.programs = programs;

        meshPool = new IrisMeshPool();
        vao = GlVertexArray.create();
        instanceTexture = new TextureBuffer();
        light = new InstancedLight();

        meshPool.bind(vao);

        oitFramebuffer = new OitFramebuffer(programs.oitPrograms());
    }

    @Override
    public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage) {
        super.render(lightStorage, environmentStorage);

        // Early exit if nothing to render
        if (instancers.isEmpty() && allDraws.isEmpty()) {
            return;
        }

        // Update and clean up instancers efficiently
        boolean hasActiveInstances = updateAndCleanupInstancers();
        
        // Remove deleted draw calls and mark for sorting if needed
        synchronized (this) {
            needSort |= allDraws.removeIf(InstancedDraw::deleted);

            // Only process sorting and preparation if we have active instances
            if (hasActiveInstances && needSort) {
                // Perform sorting and categorization
                sortAndCategorizeDraws();
            }

            // Early exit if nothing to render after cleanup
            if (allDraws.isEmpty()) {
                return;
            }
        }

        // Flush pending resources
        meshPool.flush();
        light.flush(lightStorage);

        // Bind common resources once
        Uniforms.bindAll();
        vao.bindForDraw();
        TextureBinder.bindLightAndOverlay();
        light.bind();

        // Render standard draws
        submitDraws();

        // Render OIT draws if any
        if (!oitDraws.isEmpty()) {
            renderOitDraws();
            // Rebind VAO after OIT fullscreen passes
            vao.bindForDraw();
        }

        // Reset states
        MaterialRenderState.reset();
        TextureBinder.resetLightAndOverlay();
    }

    /**
     * Updates and cleans up instancers efficiently.
     * @return True if there are active instances after cleanup
     */
    private boolean updateAndCleanupInstancers() {
        boolean hasActiveInstances = false;
        
        // Process instancers in a single pass with thread-safety
        synchronized (instancers) {
            var iterator = instancers.values().iterator();
            while (iterator.hasNext()) {
                var instancer = iterator.next();
                int instanceCount = instancer.instanceCount();
                
                if (instanceCount == 0) {
                    instancer.delete();
                    iterator.remove();
                } else {
                    // Only update buffer if there are instances
                    instancer.updateBuffer();
                    hasActiveInstances = true;
                }
            }
        }
        
        return hasActiveInstances;
    }

    /**
     * Sorts all draw calls and categorizes them into standard and OIT draws.
     */
    private void sortAndCategorizeDraws() {
        synchronized (this) {
            // Sort once with the comparator
            allDraws.sort(DRAW_COMPARATOR);

            // Clear and categorize in a single pass
            draws.clear();
            oitDraws.clear();

            for (var draw : allDraws) {
                if (draw.material().transparency() == Transparency.ORDER_INDEPENDENT) {
                    oitDraws.add(draw);
                } else {
                    draws.add(draw);
                }
            }

            needSort = false;
        }
    }

    /**
     * Renders all OIT draws using the OIT framebuffer pipeline.
     */
    private void renderOitDraws() {
        oitFramebuffer.prepare();

        // First pass: depth range
        oitFramebuffer.depthRange();
        submitOitDraws(PipelineCompiler.OitMode.DEPTH_RANGE);

        // Second pass: generate coefficients
        oitFramebuffer.renderTransmittance();
        submitOitDraws(PipelineCompiler.OitMode.GENERATE_COEFFICIENTS);

        // Third pass: evaluate and composite
        oitFramebuffer.renderDepthFromTransmittance();
        oitFramebuffer.accumulate();
        submitOitDraws(PipelineCompiler.OitMode.EVALUATE);
        oitFramebuffer.composite();
    }

    /**
     * Submits draw calls for rendering, optimizing shader and material state changes.
     * @param drawCalls List of draw calls to render
     * @param mode OIT mode to use for rendering
     * @param isOit Whether this is for OIT rendering
     */
    private void submitDrawCalls(List<InstancedDraw> drawCalls, PipelineCompiler.OitMode mode, boolean isOit) {
        var isShadow = RenderLayerEventStateManager.isRenderingShadow();
        
        // Create a local copy of the drawCalls list to ensure thread safety during rendering
        List<InstancedDraw> localDrawCalls;
        synchronized (this) {
            if (drawCalls.isEmpty()) {
                return;
            }
            localDrawCalls = new ArrayList<>(drawCalls);
        }
        
        Object lastProgram = null;
        Object lastEnvironment = null;
        Material lastMaterial = null;
        int lastProgramHashCode = 0;
        int lastEnvironmentHashCode = 0;
        int lastMaterialHashCode = 0;
        
        for (var drawCall : localDrawCalls) {
            var material = drawCall.material();
            var groupKey = drawCall.groupKey;
            var environment = groupKey.environment();

            // Get the shader program with caching
            var program = programs.get(groupKey.instanceType(), environment.contextShader(), material, mode, isShadow);
            if (program == null) {
                continue;
            }
            
            // Use hash codes for faster comparisons
            int programHashCode = System.identityHashCode(program);
            int environmentHashCode = System.identityHashCode(environment);
            int materialHashCode = System.identityHashCode(material);
            
            boolean programChanged = programHashCode != lastProgramHashCode;
            boolean environmentChanged = environmentHashCode != lastEnvironmentHashCode;
            boolean materialChanged = materialHashCode != lastMaterialHashCode;
            
            if (programChanged) {
                program.bind();
                lastProgram = program;
                lastProgramHashCode = programHashCode;
                // Force environment and material updates when program changes
                environmentChanged = true;
                materialChanged = true;
            }
            
            if (environmentChanged) {
                environment.setupDraw(program);
                lastEnvironment = environment;
                lastEnvironmentHashCode = environmentHashCode;
            }
            
            if (materialChanged) {
                uploadMaterialUniform(program, material);
                if (isOit) {
                    MaterialRenderState.setupOit(material);
                } else {
                    MaterialRenderState.setup(material);
                }
                lastMaterial = material;
                lastMaterialHashCode = materialHashCode;
            }

            // Set vertex offset
            program.setUInt("_flw_vertexOffset", drawCall.mesh().baseVertex());

            // Make instance buffer active only when program changes
            if (programChanged) {
                Samplers.INSTANCE_BUFFER.makeActive();
            }

            // Render the draw call
            drawCall.render(instanceTexture);
        }

        // Clear program state if needed
        if (lastProgram instanceof IrisFlwCompatGlProgramBase) {
            ((IrisFlwCompatGlProgramBase) lastProgram).clear();
        }
    }

    private void submitDraws() {
        submitDrawCalls(draws, PipelineCompiler.OitMode.OFF, false);
    }

    private void submitOitDraws(PipelineCompiler.OitMode mode) {
        submitDrawCalls(oitDraws, mode, true);
    }

    @Override
    public void delete() {
        instancers.values()
                .forEach(InstancedInstancer::delete);

        allDraws.forEach(InstancedDraw::delete);
        allDraws.clear();
        draws.clear();
        oitDraws.clear();

        meshPool.delete();
        instanceTexture.delete();
        programs.release();
        vao.delete();

        light.delete();

        oitFramebuffer.delete();

        super.delete();
    }

    @Override
    protected <I extends Instance> InstancedInstancer<I> create(InstancerKey<I> key) {
        return new InstancedInstancer<>(key, new AbstractInstancer.Recreate<>(key, this));
    }

    @Override
    protected <I extends Instance> void initialize(InstancerKey<I> key, InstancedInstancer<?> instancer) {
        instancer.init();

        var meshes = key.model()
                .meshes();
        synchronized (this) {
            for (int i = 0; i < meshes.size(); i++) {
                var entry = meshes.get(i);
                var mesh = meshPool.alloc(entry.mesh());

                GroupKey<?> groupKey = new GroupKey<>(key.type(), key.environment());
                InstancedDraw instancedDraw = new InstancedDraw(instancer, mesh, groupKey, entry.material(), key.bias(), i);

                allDraws.add(instancedDraw);
                needSort = true;
                instancer.addDrawCall(instancedDraw);
            }
        }
    }

    @Override
    public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
        var isShadow = RenderLayerEventStateManager.isRenderingShadow();

        // Sort draw calls into buckets, so we don't have to do as many shader binds.
        var byType = doCrumblingSort(crumblingBlocks, handle -> {
            // AbstractInstancer directly implement HandleState, so this check is valid.
            if (handle instanceof InstancedInstancer<?> instancer) {
                return instancer;
            }
            // This rejects instances that were created by a different engine,
            // and also instances that are hidden or deleted.
            return null;
        });

        if (byType.isEmpty()) {
            return;
        }

        var crumblingMaterial = SimpleMaterial.builder();

        Uniforms.bindAll();
        vao.bindForDraw();
        TextureBinder.bindLightAndOverlay();

        for (var groupEntry : byType.entrySet()) {
            var byProgress = groupEntry.getValue();

            GroupKey<?> shader = groupEntry.getKey();

            for (var progressEntry : byProgress.int2ObjectEntrySet()) {
                Samplers.CRUMBLING.makeActive();
                TextureBinder.bind(ModelBakery.BREAKING_LOCATIONS.get(progressEntry.getIntKey()));

                for (var instanceHandlePair : progressEntry.getValue()) {
                    InstancedInstancer<?> instancer = instanceHandlePair.getFirst();
                    var index = instanceHandlePair.getSecond().index;

                    for (InstancedDraw draw : instancer.draws()) {
                        CommonCrumbling.applyCrumblingProperties(crumblingMaterial, draw.material());
                        var program = programs.get(shader.instanceType(), ContextShader.CRUMBLING, crumblingMaterial, PipelineCompiler.OitMode.OFF, isShadow);
                        program.bind();
                        program.setInt("_flw_baseInstance", index);
                        uploadMaterialUniform(program, crumblingMaterial);

                        MaterialRenderState.setup(crumblingMaterial);

                        Samplers.INSTANCE_BUFFER.makeActive();

                        draw.renderOne(instanceTexture);
                        program.clear();
                    }
                }
            }
        }

        MaterialRenderState.reset();
        TextureBinder.resetLightAndOverlay();
    }

    @Override
    public void triggerFallback() {
        InstancingPrograms.kill();
        Minecraft.getInstance().levelRenderer.allChanged();
    }

    public static void uploadMaterialUniform(GlProgram program, Material material) {
        int packedFogAndCutout = MaterialEncoder.packUberShader(material);
        int packedMaterialProperties = MaterialEncoder.packProperties(material);
        program.setUVec2("_flw_packedMaterial", packedFogAndCutout, packedMaterialProperties);
    }
}