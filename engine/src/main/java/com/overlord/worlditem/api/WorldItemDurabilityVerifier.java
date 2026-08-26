package com.overlord.worlditem.api;

/** Trusted verifier for an opaque backend-issued durable proof. */
@FunctionalInterface
public interface WorldItemDurabilityVerifier {
    void verify(
            WorldItemPersistenceTicket ticket,
            WorldItemPersistencePlan plan,
            WorldItemDurableProof proof);
}
