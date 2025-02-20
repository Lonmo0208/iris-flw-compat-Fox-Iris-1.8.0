package top.leonx.irisflw.mixin.iris;

import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.properties.ShaderProperties;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.features.FeatureFlags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.leonx.irisflw.accessors.ProgramSetAccessor;

import java.util.Optional;
import java.util.function.Function;

@Mixin(ProgramSet.class)
public abstract class ProgramSetMixin implements ProgramSetAccessor {
    @Invoker(remap = false)
    @Override
    public abstract ProgramSource callReadProgramSource(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider, String program, ProgramSet programSet, ShaderProperties properties, boolean readTessellation);

    @Invoker(remap = false)
    @Override
    public abstract ProgramSource callReadProgramSource(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider, String program, ProgramSet programSet, ShaderProperties properties, BlendModeOverride var5, boolean readTessellation);

    private ProgramSource gbuffersFlw;
    private ProgramSource shadowFlw;

    @Inject(method = "<init>",remap = false,at = @At(value="RETURN"))
    private void initGBufferFlw(AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider,
                                ShaderProperties shaderProperties, ShaderPack pack, CallbackInfo ci){
        FeatureFlags tessellationFlag = FeatureFlags.getValue("TESSELLATION_SHADERS");
        if (tessellationFlag == FeatureFlags.UNKNOWN) {
            tessellationFlag = FeatureFlags.getValue("TESSELATION_SHADERS");
        }
	    boolean readTessellation = pack.hasFeature(tessellationFlag);
        gbuffersFlw = callReadProgramSource(directory, sourceProvider, "gbuffers_flw", (ProgramSet) (Object)this, shaderProperties, readTessellation);
        shadowFlw = callReadProgramSource(directory, sourceProvider, "shadow_flw", (ProgramSet) (Object)this,shaderProperties, BlendModeOverride.OFF, readTessellation);
    }

    @Override
    public Optional<ProgramSource> getGbuffersFlw() {
        return gbuffersFlw.requireValid();
    }

    @Override
    public Optional<ProgramSource> getShadowFlw() {
        return shadowFlw.requireValid();
    }
}
