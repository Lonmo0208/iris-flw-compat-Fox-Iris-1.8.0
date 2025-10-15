package top.leonx.irisflw.iris;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;

public class GlUniformMcMatrix4f extends GlUniform<Matrix4f>{
    private float[] cachedMatrix = null;
    
    public GlUniformMcMatrix4f(int index) {
        super(index);
    }

    public void set(Matrix4f value) {
        if (index < 0) {
            return;
        }
        
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.callocFloat(16);
            value.get(buf);

            float[] matrixData = new float[16];
            buf.rewind();
            buf.get(matrixData);

            if (cachedMatrix == null || !matrixEquals(cachedMatrix, matrixData)) {
                if (cachedMatrix == null) {
                    cachedMatrix = new float[16];
                }
                System.arraycopy(matrixData, 0, cachedMatrix, 0, 16);

                buf.rewind();
                GL30C.glUniformMatrix4fv(this.index, false, buf);
            }
        }
    }

    private boolean matrixEquals(float[] m1, float[] m2) {
        for (int i = 0; i < 16; i++) {
            if (Math.abs(m1[i] - m2[i]) > 1e-6f) {
                return false;
            }
        }
        return true;
    }
    

    public void clearCache() {
        cachedMatrix = null;
    }
}
