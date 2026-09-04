package com.gaia.tools.model;

import de.javagl.jgltf.impl.v2.Accessor;
import de.javagl.jgltf.impl.v2.AccessorSparse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.jupiter.api.Assertions.*;

class BufferAccessTest {
    private static GlbAdmission.Admitted triangle() throws Exception {
        return GlbAdmission.admit(new ByteArrayInputStream(GlbFixtures.triangle()));
    }

    @Test void actualInterleavedOrPackedNumbersAndIndicesAreDecoded() throws Exception {
        var data = new BufferAccess(triangle());
        assertArrayEquals(new double[]{0,0,0,1,0,0,0,1,0}, data.numbers(0,"VEC3",3,true));
        assertArrayEquals(new int[]{0,1,2}, data.indices(2,3));
        assertEquals(3, data.accessor(0).getCount());
    }

    @ParameterizedTest @ValueSource(ints={-1,3,Integer.MAX_VALUE})
    void invalidAccessorReferencesReject(int index) throws Exception {
        var data=new BufferAccess(triangle());
        assertThrows(IllegalArgumentException.class, () -> data.accessor(index));
    }

    @Test void badBufferAndViewReferencesReject() throws Exception {
        var admitted=triangle(); admitted.document().getBufferViews().get(0).setBuffer(1);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(admitted));
        var second=triangle(); second.document().getAccessors().get(0).setBufferView(3);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(second));
    }

    @Test void offsetsCountStrideAndRangesRejectBeforeDecode() throws Exception {
        for(int kind=0;kind<5;kind++) {
            var a=triangle(); var view=a.document().getBufferViews().get(0);
            var accessor=a.document().getAccessors().get(0);
            switch(kind) {
                case 0 -> view.setByteOffset(Integer.MAX_VALUE);
                case 1 -> view.setByteLength(Integer.MAX_VALUE);
                case 2 -> accessor.setByteOffset(Integer.MAX_VALUE);
                case 3 -> accessor.setCount(Integer.MAX_VALUE);
                case 4 -> view.setByteStride(252);
            }
            assertThrows(IllegalArgumentException.class, () -> new BufferAccess(a));
        }
    }

    @Test void misalignmentAndTooSmallStrideReject() throws Exception {
        var a=triangle(); a.document().getAccessors().get(0).setByteOffset(1);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(a));
        var b=triangle(); b.document().getBufferViews().get(0).setByteStride(4);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(b));
    }

    @Test void sparseAndNormalizedFloatAreExplicitlyRejected() throws Exception {
        var a=triangle(); a.document().getAccessors().get(0).setSparse(new AccessorSparse());
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(a));
        var b=triangle(); b.document().getAccessors().get(0).setNormalized(true);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(b));
    }

    @Test void missingRequiredAccessorTypeAndComponentFail() throws Exception {
        var a=triangle(); Accessor noType=new Accessor(); noType.setComponentType(5126);
        noType.setCount(3); noType.setBufferView(0); a.document().getAccessors().set(0,noType);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(a));
        var b=triangle(); Accessor noComponent=new Accessor(); noComponent.setType("VEC3");
        noComponent.setCount(3); noComponent.setBufferView(0); b.document().getAccessors().set(0,noComponent);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(b));
    }

    @Test void numericCountBudgetAndExpectedShapeCheckedBeforeAllocation() throws Exception {
        var a=new BufferAccess(triangle());
        assertThrows(IllegalArgumentException.class, () -> a.numbers(0,"VEC3",2,true));
        assertThrows(IllegalArgumentException.class, () -> a.numbers(0,"VEC2",3,true));
        assertThrows(IllegalArgumentException.class, () -> a.indices(0,3));
    }

    @Test void indexDomainAndForbiddenRestartValueReject() throws Exception {
        for(int value:new int[]{3,65535}) {
            var a=triangle(); byte[] bytes=new byte[a.binary().remaining()]; a.binary().get(bytes);
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(72,(short)value);
            var modified=new GlbAdmission.Admitted(a.document(),ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),a.receipt());
            var data=new BufferAccess(modified);
            assertThrows(IllegalArgumentException.class, () -> data.indices(2,3));
        }
    }

    @Test void nonfiniteActualNumbersRejectEvenWithFiniteDeclaredBounds() throws Exception {
        var a=triangle(); byte[] bytes=new byte[a.binary().remaining()]; a.binary().get(bytes);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putFloat(0,Float.NaN);
        var data=new BufferAccess(new GlbAdmission.Admitted(a.document(),ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN),a.receipt()));
        assertThrows(IllegalArgumentException.class, () -> data.numbers(0,"VEC3",3,true));
    }

    @Test void oversizedBinPaddingAndMultipleBuffersReject() throws Exception {
        var a=triangle(); a.document().getBuffers().get(0).setByteLength(72);
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(a));
        var b=triangle(); b.document().addBuffers(b.document().getBuffers().get(0));
        assertThrows(IllegalArgumentException.class, () -> new BufferAccess(b));
    }
}
