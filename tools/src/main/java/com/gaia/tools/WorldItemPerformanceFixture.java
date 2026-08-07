package com.gaia.tools;

import com.gaia.worlditem.PhysicalWorldItemSystem;
import com.gaia.worlditem.WorldItemPhysicsConfig;
import com.gaia.worlditem.WorldItemPhysicsMetrics;
import com.overlord.assets.ResourceLocation;
import com.overlord.core.thread.MainThreadGuard;
import com.overlord.inventory.api.ItemStack;
import com.overlord.physics.BlockCollisionShapeResolver;
import com.overlord.physics.CollisionWorld;
import com.overlord.physics.PhysicsWorld;
import com.overlord.renderer.particle.ParticleAllocationMetrics;
import com.overlord.renderer.particle.ParticleCategory;
import com.overlord.renderer.particle.ParticleEmission;
import com.overlord.renderer.particle.ParticlePriority;
import com.overlord.renderer.particle.ParticleSystem;
import com.overlord.renderer.texture.TextureRegion;
import com.overlord.voxel.ChunkKey;
import com.overlord.voxel.World;
import com.overlord.worlditem.LogicalWorldItemService;
import com.overlord.worlditem.api.WorldItemSpawnRequest;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.ListenerNotFoundException;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import org.joml.Vector3f;

/** Deterministic headless Phase 11 allocation and GC evidence fixture. */
public final class WorldItemPerformanceFixture {
    private static final ResourceLocation DIRT = ResourceLocation.parse("gaia:dirt");
    private static final TextureRegion REGION =
            new TextureRegion(DIRT, 0, 0, 16, 16, 16, 16);

    private WorldItemPerformanceFixture() {}

    public static Result run(Configuration configuration) {
        Configuration config = java.util.Objects.requireNonNull(
                configuration, "configuration");
        MainThreadGuard guard = MainThreadGuard.captureCurrentThread();
        World world = flatWorld(config.worldItemLimit());
        CollisionWorld collisions = new CollisionWorld(
                world, BlockCollisionShapeResolver.fullCubesForNonAir());
        PhysicsWorld physics = new PhysicsWorld(collisions, new Vector3f(0, -25, 0));
        LogicalWorldItemService logical = new LogicalWorldItemService(
                guard, config.worldItemLimit(), 0);
        PhysicalWorldItemSystem physical = new PhysicalWorldItemSystem(
                logical,
                physics,
                world.chunks(),
                guard,
                new WorldItemPhysicsConfig(0.50f, config.worldItemLimit()));
        ParticleSystem particles = new ParticleSystem();
        spawnGrid(logical, config.worldItemLimit(), config.seed());

        long tick = 0;
        for (int step = 0; step < config.warmupSteps(); step++) {
            advance(physical, particles, config, tick++);
        }
        particles.resetMetrics();
        AllocationCounter allocation = AllocationCounter.capture();
        long allocatedBefore = allocation.currentBytes();
        long gcCountBefore = gcCount();
        long gcTimeBefore = gcTimeMillis();
        int peakWorldItems = logical.physicalSnapshots().size();
        int peakParticles = particles.snapshot().particles().size();
        try (PauseTracker pauses = new PauseTracker()) {
            for (int step = 0; step < config.sampleSteps(); step++) {
                advance(physical, particles, config, tick++);
                peakWorldItems = Math.max(peakWorldItems, logical.physicalSnapshots().size());
                peakParticles = Math.max(
                        peakParticles, particles.snapshot().particles().size());
            }
            long allocatedAfter = allocation.currentBytes();
            long allocated = allocation.supported()
                    ? Math.max(0, allocatedAfter - allocatedBefore)
                    : 0;
            WorldItemPhysicsMetrics worldMetrics = physical.metrics();
            ParticleAllocationMetrics particleMetrics = particles.metrics();
            long simulationHash = simulationHash(
                    logical, particles, worldMetrics, particleMetrics, config.seed());
            return new Result(
                    config.worldItemLimit(),
                    config.particleLimit(),
                    config.warmupSteps(),
                    config.sampleSteps(),
                    peakWorldItems,
                    peakParticles,
                    allocation.supported(),
                    allocated,
                    Math.max(0, gcCount() - gcCountBefore),
                    Math.max(0, gcTimeMillis() - gcTimeBefore),
                    pauses.maximumPauseMillis(),
                    simulationHash,
                    worldMetrics,
                    particleMetrics);
        } finally {
            physical.close();
        }
    }

    public static void main(String[] args) {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected: seed worldItems particles warmupSteps sampleSteps");
        }
        Configuration configuration = new Configuration(
                Long.parseLong(args[0]),
                Integer.parseInt(args[1]),
                Integer.parseInt(args[2]),
                Integer.parseInt(args[4]),
                Integer.parseInt(args[3]));
        System.out.println(run(configuration).format());
    }

    private static World flatWorld(int itemLimit) {
        World world = new World();
        int side = (int) Math.ceil(Math.sqrt(itemLimit));
        int chunkSide = Math.max(1, (side + 15) / 16);
        for (int chunkX = 0; chunkX < chunkSide; chunkX++) {
            for (int chunkZ = 0; chunkZ < chunkSide; chunkZ++) {
                world.generate(new ChunkKey(chunkX, chunkZ), chunk -> {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            chunk.setBlock(x, 0, z, (byte) 1);
                        }
                    }
                });
            }
        }
        return world;
    }

    private static void spawnGrid(
            LogicalWorldItemService logical, int count, long seed) {
        int side = (int) Math.ceil(Math.sqrt(count));
        for (int index = 0; index < count; index++) {
            int x = index % side;
            int z = index / side;
            double velocity = signed(seed + index) * 0.25;
            logical.spawn(new WorldItemSpawnRequest(
                    new ItemStack(DIRT, 1),
                    x + 0.5,
                    2.0 + (index % 3) * 0.05,
                    z + 0.5,
                    velocity,
                    0,
                    -velocity,
                    Optional.empty(),
                    0));
        }
    }

    private static void advance(
            PhysicalWorldItemSystem physical,
            ParticleSystem particles,
            Configuration config,
            long tick) {
        physical.step(tick);
        particles.fixedUpdate(ParticleSystem.FIXED_STEP_SECONDS);
        int active = particles.snapshot().particles().size();
        if (active < config.particleLimit()) {
            int count = Math.min(6, config.particleLimit() - active);
            particles.emit(new ParticleEmission(
                    ParticleCategory.PICKUP_COMMITTED,
                    ParticlePriority.HIGH,
                    signed(config.seed() + tick) * 4.0f,
                    2.0f,
                    signed(config.seed() - tick) * 4.0f,
                    REGION,
                    count,
                    config.seed() ^ tick));
        }
    }

    private static long simulationHash(
            LogicalWorldItemService logical,
            ParticleSystem particles,
            WorldItemPhysicsMetrics worldMetrics,
            ParticleAllocationMetrics particleMetrics,
            long seed) {
        long hash = seed;
        for (var snapshot : logical.physicalSnapshots()) {
            var item = snapshot.runtime().item();
            hash = hash * 31 + item.id().value();
            hash = hash * 31 + Double.doubleToLongBits(item.positionX());
            hash = hash * 31 + Double.doubleToLongBits(item.positionY());
            hash = hash * 31 + item.revision();
        }
        for (var particle : particles.snapshot().particles()) {
            hash = hash * 31 + particle.spawnSequence();
            hash = hash * 31 + Float.floatToIntBits(particle.x());
        }
        hash = hash * 31 + worldMetrics.appliedWrites();
        hash = hash * 31 + particleMetrics.particleStatesCreated();
        return hash;
    }

    private static float signed(long value) {
        long mixed = value;
        mixed = (mixed ^ (mixed >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (float) ((mixed >>> 40) * 0x1.0p-23 - 1.0);
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionCount() >= 0) {
                total += collector.getCollectionCount();
            }
        }
        return total;
    }

    private static long gcTimeMillis() {
        long total = 0;
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (collector.getCollectionTime() >= 0) {
                total += collector.getCollectionTime();
            }
        }
        return total;
    }

    public record Configuration(
            long seed,
            int worldItemLimit,
            int particleLimit,
            int sampleSteps,
            int warmupSteps) {
        public Configuration {
            if (worldItemLimit <= 0 || worldItemLimit > 1024) {
                throw new IllegalArgumentException("worldItemLimit must be within 1..1024");
            }
            if (particleLimit <= 0 || particleLimit > ParticleSystem.MAX_PARTICLES) {
                throw new IllegalArgumentException("particleLimit is outside the engine cap");
            }
            if (sampleSteps <= 0 || warmupSteps < 0) {
                throw new IllegalArgumentException(
                        "sampleSteps must be positive and warmupSteps non-negative");
            }
        }
    }

    public record Result(
            int worldItemLimit,
            int particleLimit,
            int warmupSteps,
            int sampleSteps,
            int peakWorldItems,
            int peakParticles,
            boolean allocationSupported,
            long allocatedBytes,
            long gcCollectionDelta,
            long gcCollectionTimeMillis,
            long maximumGcPauseMillis,
            long simulationHash,
            WorldItemPhysicsMetrics worldMetrics,
            ParticleAllocationMetrics particleMetrics) {
        public String format() {
            return "worldItems=" + worldItemLimit
                    + " particles=" + particleLimit
                    + " warmupSteps=" + warmupSteps
                    + " sampleSteps=" + sampleSteps
                    + " peakWorldItems=" + peakWorldItems
                    + " peakParticles=" + peakParticles
                    + " allocationSupported=" + allocationSupported
                    + " allocatedBytes=" + allocatedBytes
                    + " gcCollections=" + gcCollectionDelta
                    + " gcTimeMillis=" + gcCollectionTimeMillis
                    + " maxGcPauseMillis=" + maximumGcPauseMillis
                    + " simulationHash=" + simulationHash;
        }
    }

    private record AllocationCounter(
            boolean supported,
            com.sun.management.ThreadMXBean bean,
            long threadId) {
        private static AllocationCounter capture() {
            java.lang.management.ThreadMXBean platform = ManagementFactory.getThreadMXBean();
            if (!(platform instanceof com.sun.management.ThreadMXBean extended)
                    || !extended.isThreadAllocatedMemorySupported()) {
                return new AllocationCounter(false, null, Thread.currentThread().getId());
            }
            if (!extended.isThreadAllocatedMemoryEnabled()) {
                extended.setThreadAllocatedMemoryEnabled(true);
            }
            return new AllocationCounter(
                    extended.isThreadAllocatedMemoryEnabled(),
                    extended,
                    Thread.currentThread().getId());
        }

        private long currentBytes() {
            return supported ? Math.max(0, bean.getThreadAllocatedBytes(threadId)) : 0;
        }
    }

    private static final class PauseTracker implements AutoCloseable {
        private final AtomicLong maximumPauseMillis = new AtomicLong();
        private final List<Registration> registrations = new ArrayList<>();

        private PauseTracker() {
            NotificationListener listener = (notification, handback) -> {
                if (!com.sun.management.GarbageCollectionNotificationInfo
                        .GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) {
                    return;
                }
                Object userData = notification.getUserData();
                if (userData instanceof CompositeData data) {
                    long duration = com.sun.management.GarbageCollectionNotificationInfo
                            .from(data).getGcInfo().getDuration();
                    maximumPauseMillis.accumulateAndGet(duration, Math::max);
                }
            };
            for (GarbageCollectorMXBean collector :
                    ManagementFactory.getGarbageCollectorMXBeans()) {
                if (collector instanceof NotificationEmitter emitter) {
                    emitter.addNotificationListener(listener, null, null);
                    registrations.add(new Registration(emitter, listener));
                }
            }
        }

        private long maximumPauseMillis() {
            return maximumPauseMillis.get();
        }

        @Override
        public void close() {
            for (Registration registration : registrations) {
                try {
                    registration.emitter().removeNotificationListener(
                            registration.listener());
                } catch (ListenerNotFoundException ignored) {
                    // Already detached by the owning management implementation.
                }
            }
        }
    }

    private record Registration(
            NotificationEmitter emitter,
            NotificationListener listener) {}
}
