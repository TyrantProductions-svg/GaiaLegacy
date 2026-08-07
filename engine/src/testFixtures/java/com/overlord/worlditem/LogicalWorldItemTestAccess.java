package com.overlord.worlditem;

import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.lang.reflect.Field;
import java.util.Map;

/** Test-only access for establishing otherwise unreachable revision boundary fixtures. */
public final class LogicalWorldItemTestAccess {
    private LogicalWorldItemTestAccess() {
    }

    public static void forceRevision(
            LogicalWorldItemService service, WorldItemId itemId, long revision) {
        try {
            Field itemsField = LogicalWorldItemService.class.getDeclaredField("items");
            itemsField.setAccessible(true);
            Map<?, ?> items = (Map<?, ?>) itemsField.get(service);
            Object state = items.get(itemId);
            if (state == null) {
                throw new AssertionError("missing logical world item " + itemId);
            }

            Field itemField = state.getClass().getDeclaredField("item");
            itemField.setAccessible(true);
            WorldItemSnapshot current = (WorldItemSnapshot) itemField.get(state);
            itemField.set(state, new WorldItemSnapshot(
                    current.id(),
                    current.stack(),
                    current.positionX(),
                    current.positionY(),
                    current.positionZ(),
                    current.velocityX(),
                    current.velocityY(),
                    current.velocityZ(),
                    revision));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("cannot establish revision boundary fixture", failure);
        }
    }
}
