package com.overlord.voxel;

import com.overlord.renderer.material.RenderType;
import java.util.Objects;

public final class VoxelAmbientOcclusion {
    private static final float BOTH_SIDES = 0.45f;
    private static final float TWO_SAMPLES = 0.65f;
    private static final float ONE_SAMPLE = 0.82f;
    private static final float NO_SAMPLES = 1.0f;

    private VoxelAmbientOcclusion() {}

    public static float sample(
            ChunkMeshInput input,
            BlockRenderResolver resolver,
            int blockX,
            int blockY,
            int blockZ,
            BlockFace face,
            int tangentSignA,
            int tangentSignB) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(face, "face");
        requireTangentSign(tangentSignA, "tangentSignA");
        requireTangentSign(tangentSignB, "tangentSignB");

        FaceBasis basis = basis(face);
        int surfaceX = blockX + basis.normalX();
        int surfaceY = blockY + basis.normalY();
        int surfaceZ = blockZ + basis.normalZ();
        boolean sideA =
                occludes(
                        input,
                        resolver,
                        surfaceX + tangentSignA * basis.tangentAX(),
                        surfaceY + tangentSignA * basis.tangentAY(),
                        surfaceZ + tangentSignA * basis.tangentAZ());
        boolean sideB =
                occludes(
                        input,
                        resolver,
                        surfaceX + tangentSignB * basis.tangentBX(),
                        surfaceY + tangentSignB * basis.tangentBY(),
                        surfaceZ + tangentSignB * basis.tangentBZ());
        if (sideA && sideB) {
            return BOTH_SIDES;
        }

        boolean corner =
                occludes(
                        input,
                        resolver,
                        surfaceX
                                + tangentSignA * basis.tangentAX()
                                + tangentSignB * basis.tangentBX(),
                        surfaceY
                                + tangentSignA * basis.tangentAY()
                                + tangentSignB * basis.tangentBY(),
                        surfaceZ
                                + tangentSignA * basis.tangentAZ()
                                + tangentSignB * basis.tangentBZ());
        int occluderCount =
                (sideA ? 1 : 0)
                        + (sideB ? 1 : 0)
                        + (corner ? 1 : 0);
        return switch (occluderCount) {
            case 0 -> NO_SAMPLES;
            case 1 -> ONE_SAMPLE;
            case 2 -> TWO_SAMPLES;
            default -> throw new IllegalStateException(
                    "both sides must take precedence");
        };
    }

    private static boolean occludes(
            ChunkMeshInput input,
            BlockRenderResolver resolver,
            int x,
            int y,
            int z) {
        byte block = input.getBlock(x, y, z);
        if (block == 0) {
            return false;
        }
        BlockRenderInfo renderInfo =
                resolver.resolve(Byte.toUnsignedInt(block));
        return renderInfo.renderable()
                && renderInfo.material().renderType()
                        != RenderType.TRANSPARENT;
    }

    private static void requireTangentSign(
            int sign, String parameterName) {
        if (sign != -1 && sign != 1) {
            throw new IllegalArgumentException(
                    parameterName + " must be -1 or 1");
        }
    }

    private static FaceBasis basis(BlockFace face) {
        return switch (face) {
            case NORTH -> new FaceBasis(0, 0, -1, 1, 0, 0, 0, 1, 0);
            case SOUTH -> new FaceBasis(0, 0, 1, 1, 0, 0, 0, 1, 0);
            case UP -> new FaceBasis(0, 1, 0, 1, 0, 0, 0, 0, 1);
            case DOWN -> new FaceBasis(0, -1, 0, 1, 0, 0, 0, 0, 1);
            case WEST -> new FaceBasis(-1, 0, 0, 0, 0, 1, 0, 1, 0);
            case EAST -> new FaceBasis(1, 0, 0, 0, 0, 1, 0, 1, 0);
        };
    }

    private record FaceBasis(
            int normalX,
            int normalY,
            int normalZ,
            int tangentAX,
            int tangentAY,
            int tangentAZ,
            int tangentBX,
            int tangentBY,
            int tangentBZ) {}
}
