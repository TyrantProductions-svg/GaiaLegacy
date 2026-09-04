package com.gaia.tools.model;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
import static com.gaia.tools.model.GlbFixtures.*;

class AdmittedDataTest {
    @Test
    void handoffUsesSameOwnedBytesAndReadOnlyIndependentBinViews() throws Exception {
        byte[] input = triangle();
        var stream = new ByteArrayInputStream(input);
        var admitted = GlbAdmission.admit(stream);
        Arrays.fill(input, (byte) 0);
        assertEquals(0, stream.available());
        assertEquals(0, admitted.document().getScene());
        assertEquals("GAIA_ASSET_ROOT", admitted.document().getNodes().get(0).getName());
        assertEquals(1f, admitted.binary().getFloat(12));
        assertThrows(ReadOnlyBufferException.class, () -> admitted.binary().put(0, (byte) 2));
        admitted.binary().position(4);
        assertEquals(0, admitted.binary().position());
        assertEquals("PREFLIGHT_AND_ASSET_MAPPING_ONLY", admitted.receipt().scope());
    }

    @Test
    void handoffNeverBypassesStrictMappingOrSecurityPreflight() {
        for (String fields : new String[]{"\"scene\":0.5", "\"nodes\":[{\"name\":123}]",
                "\"buffers\":false,\"extras\":{\"uri\":\"file:///unused\"}"}) {
            var failure = assertThrows(PreflightException.class, () -> GlbAdmission.admit(
                    new ByteArrayInputStream(glb("{\"asset\":{\"version\":\"2.0\"}," + fields + "}"))));
            assertEquals(fields.contains("uri") ? PreflightException.Code.URI_FORBIDDEN
                    : PreflightException.Code.DECODE_REJECTED, failure.code());
        }
    }
}
