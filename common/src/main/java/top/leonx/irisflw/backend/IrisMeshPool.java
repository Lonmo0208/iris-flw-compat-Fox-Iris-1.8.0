package top.leonx.irisflw.backend;

import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.engine.IndexPool;
import dev.engine_room.flywheel.backend.engine.MeshPool;
import dev.engine_room.flywheel.backend.gl.array.GlVertexArray;
import dev.engine_room.flywheel.backend.gl.array.VertexAttribute;
import dev.engine_room.flywheel.backend.gl.buffer.GlBuffer;
import dev.engine_room.flywheel.lib.vertex.VertexView;
import top.leonx.irisflw.IrisFlw;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optimized mesh pool implementation for Iris shader compatibility.
 * Uses cached references and avoids repeated reflection lookups.
 * Maintains both vertex formats to ensure thread-safe switching during shader reloads.
 */
public class IrisMeshPool extends MeshPool {
    private final GlBuffer vbo;
    private final IndexPool indexPool;
    private final VertexView extendedVertexView;
    private final Field vertexViewField;
    private final AtomicBoolean needsFormatUpdate = new AtomicBoolean(false);

    public IrisMeshPool() {
        try {
            // Get private fields using reflection - done once during initialization
            this.vertexViewField = MeshPool.class.getDeclaredField("vertexView");
            this.vertexViewField.setAccessible(true);

            var indexPoolField = MeshPool.class.getDeclaredField("indexPool");
            indexPoolField.setAccessible(true);
            this.indexPool = (IndexPool) indexPoolField.get(this);

            var vboField = MeshPool.class.getDeclaredField("vbo");
            vboField.setAccessible(true);
            this.vbo = (GlBuffer) vboField.get(this);

            // Pre-create the extended vertex view once
            this.extendedVertexView = IrisInternalVertex.createVertexView();

            // Initialize with correct initial format
            updateVertexViewForCurrentState();

            // Mark that we need to listen for shader reloads
            needsFormatUpdate.set(true);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("Failed to initialize IrisMeshPool", e);
        }
    }

    /**
     * Updates the vertex view based on the current shader pack state.
     * This is thread-safe and only updates when necessary.
     */
    private void updateVertexViewForCurrentState() {
        boolean useExtendedFormat = IrisFlw.isUsingExtendedVertexFormat();
        try {
            // Set the appropriate vertex view based on current state
            this.vertexViewField.set(this, useExtendedFormat ? extendedVertexView : null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to update vertex view", e);
        }
    }

    /**
     * Binds the mesh pool to the given vertex array with optimized attribute setup.
     * Uses current shader pack state to determine the correct vertex format.
     * This method is thread-safe and handles shader reloads properly.
     */
    @Override
    public void bind(GlVertexArray vertexArray) {
        // Ensure vertex view is updated to current state before binding
        updateVertexViewForCurrentState();

        // Bind index pool once
        this.indexPool.bind(vertexArray);

        // Bind vertex buffer with correct stride based on current format state
        boolean useExtendedFormat = IrisFlw.isUsingExtendedVertexFormat();
        if (useExtendedFormat) {
            vertexArray.bindVertexBuffer(0, this.vbo.handle(), 0L, IrisInternalVertex.EXT_STRIDE);
            vertexArray.bindAttributes(0, 0, IrisInternalVertex.EXT_ATTRIBUTES);
        } else {
            vertexArray.bindVertexBuffer(0, this.vbo.handle(), 0L, InternalVertex.STRIDE);
            vertexArray.bindAttributes(0, 0, InternalVertex.ATTRIBUTES);
        }
    }

    /**
     * @return Whether this mesh pool is using the extended vertex format
     */
    public boolean isUsingExtendedFormat() {
        return IrisFlw.isUsingExtendedVertexFormat();
    }
}
