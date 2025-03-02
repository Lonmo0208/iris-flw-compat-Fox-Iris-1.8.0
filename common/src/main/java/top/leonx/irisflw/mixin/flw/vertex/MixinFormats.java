package top.leonx.irisflw.mixin.flw.vertex;

import dev.engine_room.flywheel.core.Formats;
import dev.engine_room.flywheel.core.vertex.BlockVertex;
import dev.engine_room.flywheel.core.vertex.PosTexNormalVertex;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import top.leonx.irisflw.vertex.block.ExtendedBlockVertex;
import top.leonx.irisflw.vertex.postexnormal.ExtendedPosTexNormalVertex;

/**
 * Mixin to replace the default vertex formats with the extended formats.
 */
@Mixin(Formats.class)
public class MixinFormats {

    @SuppressWarnings("ShadowTarget")
    @Final
    @Shadow(remap = false)
    public static BlockVertex BLOCK = new ExtendedBlockVertex();

    @SuppressWarnings("ShadowTarget")
    @Final
    @Shadow(remap = false)
    public static final PosTexNormalVertex POS_TEX_NORMAL = new ExtendedPosTexNormalVertex();
}
