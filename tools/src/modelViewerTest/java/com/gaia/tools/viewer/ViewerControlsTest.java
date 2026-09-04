package com.gaia.tools.viewer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ViewerControlsTest {
    @Test
    void frameAppliesCameraMotionTogglesAndPressedEdgeIntentsOnce() throws Exception {
        var snapshot = ViewerFixtures.snapshot(false, false);
        OrbitCamera camera = new OrbitCamera(snapshot.bounds(), 1280, 720);
        ViewerControls controls = new ViewerControls();
        double initialYaw = camera.yaw();
        double initialDistance = camera.distance();

        ViewerControls.Intents intents = controls.apply(new ViewerInput.Frame(
                10, -5, 3, 4, 2, false, true, true, true, true, true, true),
                camera, 720);

        assertTrue(intents.reload());
        assertTrue(intents.close());
        assertTrue(camera.yaw() != initialYaw);
        assertTrue(camera.distance() != initialDistance);
        assertFalse(controls.visibility().grid());
        assertFalse(controls.visibility().axes());
        assertFalse(controls.visibility().bounds());
        assertTrue(controls.visibility().wireframe());
        assertEquals(ViewerControls.Intents.none(), controls.apply(ViewerInput.Frame.idle(), camera, 720));
    }

    @Test
    void frameIntentRefitsCurrentBoundsWithoutChangingProfileData() throws Exception {
        var snapshot = ViewerFixtures.snapshot(false, false);
        OrbitCamera camera = new OrbitCamera(snapshot.bounds(), 1280, 720);
        ViewerControls controls = new ViewerControls();
        camera.pan(80, 40, 720);

        controls.apply(new ViewerInput.Frame(0,0,0,0,0,
                true,false,false,false,false,false,false), camera, 720);

        assertEquals(3.5, camera.target().x, 1.0e-9);
        assertEquals(0.5, camera.target().y, 1.0e-9);
    }
}
