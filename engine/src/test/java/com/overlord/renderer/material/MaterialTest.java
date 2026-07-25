package com.overlord.renderer.material;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.overlord.assets.ResourceLocation;
import com.overlord.renderer.TextureBinding;
import com.overlord.renderer.shader.ShaderBinding;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class MaterialTest {
    @Test
    void preservesNonOwningBindingsAndDefinitionIdentity()
            throws Exception {
        MaterialDefinition definition =
                new MaterialDefinition(
                        ResourceLocation.parse("test:opaque"),
                        ResourceLocation.parse("test:blocks"),
                        RenderType.OPAQUE,
                        0.5f,
                        ResourceLocation.parse("test:missing"));
        ShaderBinding fakeShader = new FakeShaderBinding();
        TextureBinding fakeTexture = textureUnit -> {};

        Material material =
                new Material(definition, fakeShader, fakeTexture);

        assertSame(definition, material.definition());
        assertSame(fakeShader, material.shader());
        assertSame(fakeTexture, material.texture());
        assertThrows(
                NoSuchMethodException.class,
                () -> Material.class.getMethod("cleanup"));
        assertThrows(
                NoSuchMethodException.class,
                () -> Material.class.getMethod("close"));
    }

    private static final class FakeShaderBinding
            implements ShaderBinding {
        @Override
        public int programId() {
            return 0;
        }

        @Override
        public void use() {}

        @Override
        public void setMatrix4(String uniform, Matrix4fc value) {}

        @Override
        public void setInt(String uniform, int value) {}

        @Override
        public void setFloat(String uniform, float value) {}

        @Override
        public void setVector3(String uniform, Vector3fc value) {}
    }
}
