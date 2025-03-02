package top.leonx.irisflw.mixin.flw;

import dev.engine_room.flywheel.core.compile.Template;
import dev.engine_room.flywheel.core.compile.VertexCompiler;
import dev.engine_room.flywheel.core.compile.VertexData;
import dev.engine_room.flywheel.core.source.FileResolution;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexCompiler.class)
public interface AccessorVertexCompiler {
    @Accessor(remap = false)
    Template<? extends VertexData> getTemplate();

    @Accessor(remap = false)
    FileResolution getHeader();
}
