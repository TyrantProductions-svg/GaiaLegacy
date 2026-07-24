package com.overlord.inventory.testing;

import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.InventoryChangeRequest;
import com.overlord.inventory.api.InventoryChangeResult;
import com.overlord.inventory.api.InventoryService;
import com.overlord.inventory.api.InventoryView;
import java.util.Objects;

public final class StubInventoryService
        implements InventoryService {
    private InventoryView snapshot;
    private InventoryChangeResult replacementResult;
    private InventoryChangeRequest lastRequest;

    public StubInventoryService(
            InventoryView snapshot,
            InventoryChangeResult replacementResult) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.replacementResult =
                Objects.requireNonNull(
                        replacementResult, "replacementResult");
    }

    @Override
    public InventoryView snapshot(EntityRef owner) {
        Objects.requireNonNull(owner, "owner");
        return snapshot;
    }

    @Override
    public InventoryChangeResult replaceSlot(
            InventoryChangeRequest request) {
        lastRequest = Objects.requireNonNull(request, "request");
        return replacementResult;
    }

    public InventoryChangeRequest lastRequest() {
        return lastRequest;
    }
}
