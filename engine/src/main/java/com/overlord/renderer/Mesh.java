package com.overlord.renderer;

import static org.lwjgl.opengl.GL30C.*;

import com.overlord.core.thread.MainThreadGuard;
import com.overlord.voxel.ChunkMeshData;
import com.overlord.voxel.VoxelVertexAttribute;
import com.overlord.voxel.VoxelVertexFormat;
import java.nio.FloatBuffer;
import java.util.Objects;
import org.lwjgl.system.MemoryUtil;

public class Mesh implements ChunkGpuMesh {
    private final MainThreadGuard mainThreadGuard;
    private int vaoId;
    private int vboId;
    private final int vertexCount;

    public Mesh(MainThreadGuard mainThreadGuard, ChunkMeshData data) {
        this.mainThreadGuard = Objects.requireNonNull(mainThreadGuard, "mainThreadGuard");
        this.mainThreadGuard.assertMainThread("mesh GPU upload");
        ChunkMeshData required = Objects.requireNonNull(data, "data");
        this.vertexCount = required.vertexCount();

        FloatBuffer vertexBuffer = null;
        try {
            vaoId = glGenVertexArrays();
            glBindVertexArray(vaoId);

            vboId = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, vboId);

            int floatCount = Math.toIntExact(
                    required.outputByteSize() / Float.BYTES);
            vertexBuffer = MemoryUtil.memAllocFloat(floatCount);
            required.copyVerticesTo(vertexBuffer);
            vertexBuffer.flip();
            glBufferData(GL_ARRAY_BUFFER, vertexBuffer, GL_STATIC_DRAW);

            for (VoxelVertexAttribute attribute
                    : VoxelVertexFormat.attributes()) {
                glVertexAttribPointer(
                        attribute.location(),
                        attribute.componentCount(),
                        GL_FLOAT,
                        false,
                        VoxelVertexFormat.STRIDE_BYTES,
                        attribute.byteOffset());
                glEnableVertexAttribArray(attribute.location());
            }

            glBindBuffer(GL_ARRAY_BUFFER, 0);
            glBindVertexArray(0);
        } catch (RuntimeException | Error failure) {
            if (vboId != 0) {
                glDeleteBuffers(vboId);
                vboId = 0;
            }
            if (vaoId != 0) {
                glDeleteVertexArrays(vaoId);
                vaoId = 0;
            }
            throw failure;
        } finally {
            if (vertexBuffer != null) {
                MemoryUtil.memFree(vertexBuffer);
            }
        }
    }

    @Override
    public int vertexCount() {
        return vertexCount;
    }

    @Override
    public void draw() {
        mainThreadGuard.assertMainThread("mesh draw");
        glBindVertexArray(vaoId);
        glDrawArrays(GL_TRIANGLES, 0, vertexCount);
        glBindVertexArray(0);
    }

    @Override
    public void cleanup() {
        mainThreadGuard.assertMainThread("mesh cleanup");
        if (vboId != 0) {
            glDeleteBuffers(vboId);
            vboId = 0;
        }
        if (vaoId != 0) {
            glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
    }
}
