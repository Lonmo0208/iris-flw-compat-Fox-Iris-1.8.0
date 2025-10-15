package top.leonx.irisflw.iris;

import net.caffeinemc.mods.sodium.client.gl.shader.uniform.GlUniform;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class GlUniformMcMatrix4f extends GlUniform<Matrix4f>{
    private static final int MATRIX_SIZE = 16;
    private static final float EPSILON = 1e-6f;

    private final AtomicReference<float[]> cachedMatrixRef = new AtomicReference<>(null);
    private final AtomicInteger modificationCounter = new AtomicInteger(0);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    public GlUniformMcMatrix4f(int index) {
        super(index);
    }

    public void set(Matrix4f value) {
        if (index < 0) {
            return;
        }

        float[] matrixData = new float[MATRIX_SIZE];

        value.get(matrixData);

        boolean needsUpdate = compareAndUpdateMatrix(matrixData);
        
        if (needsUpdate) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                FloatBuffer buf = stack.callocFloat(MATRIX_SIZE);
                buf.put(matrixData);
                buf.rewind();
                GL30C.glUniformMatrix4fv(this.index, false, buf);
            }
        }
    }

    private boolean compareAndUpdateMatrix(float[] newMatrix) {
        float[] currentMatrix = cachedMatrixRef.get();

        if (currentMatrix == null) {
            lock.writeLock().lock();
            try {
                if (cachedMatrixRef.get() == null) {
                    cachedMatrixRef.set(newMatrix.clone());
                    modificationCounter.incrementAndGet();
                    return true;
                }
                currentMatrix = cachedMatrixRef.get();
            } finally {
                lock.writeLock().unlock();
            }
        }

        if (!quickCompareMatrices(currentMatrix, newMatrix)) {
            lock.writeLock().lock();
            try {
                currentMatrix = cachedMatrixRef.get();
                if (!matrixEquals(currentMatrix, newMatrix)) {
                    cachedMatrixRef.set(newMatrix.clone());
                    modificationCounter.incrementAndGet();
                    return true;
                }
            } finally {
                lock.writeLock().unlock();
            }
        }
        
        return false;
    }

    private boolean quickCompareMatrices(float[] m1, float[] m2) {
        for (int i = 0; i < MATRIX_SIZE; i += 5) {
            if (Math.abs(m1[i] - m2[i]) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    private boolean matrixEquals(float[] m1, float[] m2) {
        for (int i = 0; i < MATRIX_SIZE; i++) {
            if (Math.abs(m1[i] - m2[i]) > EPSILON) {
                return false;
            }
        }
        return true;
    }

    public int getModificationCounter() {
        return modificationCounter.get();
    }

    public void clearCache() {
        lock.writeLock().lock();
        try {
            cachedMatrixRef.set(null);
            modificationCounter.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
