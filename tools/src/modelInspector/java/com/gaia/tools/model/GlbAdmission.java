package com.gaia.tools.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.DelegatingDeserializer;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.LogicalType;
import de.javagl.jgltf.impl.v2.GlTF;
import de.javagl.jgltf.model.io.v2.GltfAssetV2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Offline dependency admission only. Success is NOT HAND_TOOL_V0 conformance. */
public final class GlbAdmission {
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // Jackson handles these conversions separately from general scalar coercion.
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .withCoercionConfigDefaults(config -> config.setAcceptBlankAsEmpty(false))
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .addModule(floatingTokenGuards())
            .build();

    private GlbAdmission() { }

    private static SimpleModule floatingTokenGuards() {
        // Pinned Jackson accepts quoted special doubles and packed double[] strings
        // before consulting coercion settings. Guard token shape, not numeric semantics.
        return new SimpleModule().setDeserializerModifier(new BeanDeserializerModifier() {
            @Override
            public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config,
                    BeanDescription bean, JsonDeserializer<?> delegate) {
                Class<?> type = bean.getBeanClass();
                return type == Double.class || type == double.class
                        ? new FloatingTokenGuard(delegate, false) : delegate;
            }

            @Override
            public JsonDeserializer<?> modifyArrayDeserializer(DeserializationConfig config,
                    ArrayType type, BeanDescription bean, JsonDeserializer<?> delegate) {
                return type.getRawClass() == double[].class
                        ? new FloatingTokenGuard(delegate, true) : delegate;
            }
        });
    }

    private static final class FloatingTokenGuard extends DelegatingDeserializer {
        private final boolean array;

        FloatingTokenGuard(JsonDeserializer<?> delegate, boolean array) {
            super(delegate);
            this.array = array;
        }

        @Override
        protected JsonDeserializer<?> newDelegatingInstance(JsonDeserializer<?> delegate) {
            return new FloatingTokenGuard(delegate, array);
        }

        @Override
        public Object deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.hasToken(JsonToken.VALUE_NULL)) { return getNullValue(context); }
            if (array) {
                if (!parser.isExpectedStartArrayToken()) {
                    return context.reportInputMismatch(handledType(), "Expected a JSON numeric array");
                }
                // The pinned primitive-array deserializer advances via nextToken;
                // delegate its allocation/conversion/null behavior without buffering a copy.
                return super.deserialize(new JsonParserDelegate(parser) {
                    @Override
                    public JsonToken nextToken() throws IOException {
                        JsonToken token = super.nextToken();
                        if (token == JsonToken.VALUE_STRING) {
                            context.reportInputMismatch(double[].class,
                                    "String tokens are not numeric array elements");
                        }
                        return token;
                    }
                }, context);
            }
            if (!parser.currentToken().isNumeric()) {
                return context.reportInputMismatch(handledType(), "Expected a JSON number");
            }
            return super.deserialize(parser, context);
        }
    }

    public static Receipt check(InputStream source) throws IOException {
        return admit(source).receipt();
    }

    /** Exclusive package-local handoff; DTO is transient input, never validated output. */
    static Admitted admit(InputStream source) throws IOException {
        // Must finish before constructing or invoking the semantic reader.
        GlbPreflight.CheckedGlb checked = GlbPreflight.read(source);
        try (InputStream snapshot = checked.openJsonStream()) {
            // JglTF 3.0.1's reader can log and swallow property mapping errors despite
            // setJsonErrorConsumer. Use strict binding to its PUBLIC v2 DTOs instead.
            // No Jackson default typing, JglTF internal JacksonUtils, or reference resolver.
            GlTF document = MAPPER.readValue(snapshot, GlTF.class);
            var asset = new GltfAssetV2(document, checked.binaryData());
            if (!asset.getReferences().isEmpty()) {
                throw new IllegalArgumentException("Unexpected asset mapping");
            }
            // Do not call GltfModels.create, expand accessors, or decode images here.
            return new Admitted(document, checked.binaryData(),
                    new Receipt(checked.sha256(), checked.byteLength(),
                            checked.jsonByteLength(), checked.binaryByteLength()));
        } catch (IOException | RuntimeException rejected) {
            throw new PreflightException(PreflightException.Code.DECODE_REJECTED);
        }
    }

    record Admitted(GlTF document, ByteBuffer binary, Receipt receipt) {
        @Override public ByteBuffer binary() {
            return binary == null ? ByteBuffer.allocate(0).asReadOnlyBuffer()
                    : binary.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    public record Receipt(String sha256, int byteLength, int jsonByteLength, int binaryByteLength) {
        public String profile() { return "GAIA_GLB_HAND_TOOL_V0"; }
        public String scope() { return "PREFLIGHT_AND_ASSET_MAPPING_ONLY"; }
    }
}
