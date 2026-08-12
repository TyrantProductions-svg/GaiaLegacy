package com.gaia.save.codec;

import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.WorldItemsSaveSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.overlord.assets.ResourceLocation;
import com.overlord.config.GameConfig;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.ItemStack;
import com.overlord.worlditem.api.WorldItemId;
import com.overlord.worlditem.api.WorldItemPhysicalState;
import com.overlord.worlditem.api.WorldItemRestoreEntry;
import com.overlord.worlditem.api.WorldItemRuntimeSnapshot;
import com.overlord.worlditem.api.WorldItemSnapshot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Deterministic JSON codec for canonical logical world-item state. */
public final class WorldItemsSectionCodec
        implements SaveSectionCodec<WorldItemsSaveSnapshot> {
    private static final int CODEC_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES =
            GameConfig.Interaction.MAX_LOGICAL_WORLD_ITEMS;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "fixedTick", "nextItemId", "itemIdsExhausted", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "id",
            "stack",
            "positionX",
            "positionY",
            "positionZ",
            "velocityX",
            "velocityY",
            "velocityZ",
            "revision",
            "source",
            "spawnTick",
            "pickupAvailableTick",
            "physicalState");
    private static final Set<String> STACK_FIELDS = Set.of("itemId", "count");
    private static final Comparator<WorldItemRestoreEntry> ENTRY_ORDER =
            Comparator.comparingLong(entry -> entry.runtime().item().id().value());

    @Override
    public SaveSectionId sectionId() {
        return SaveSectionId.WORLD_ITEMS;
    }

    @Override
    public int codecVersion() {
        return CODEC_VERSION;
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public byte[] encode(WorldItemsSaveSnapshot snapshot) {
        try {
            WorldItemsSaveSnapshot value =
                    Objects.requireNonNull(snapshot, "snapshot");
            List<WorldItemRestoreEntry> entries = new ArrayList<>(value.entries());
            if (entries.size() > MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "World-item count exceeds supported capacity");
            }
            entries.sort(ENTRY_ORDER);
            validateEntries(entries);
            return JsonCodecSupport.write(
                    writer -> writeDocument(writer, value, entries));
        } catch (RuntimeException failure) {
            throw new SaveCodecException(
                    "world-items.invalid-snapshot",
                    "Invalid world-items snapshot",
                    failure);
        }
    }

    @Override
    public WorldItemsSaveSnapshot decode(byte[] bytes) {
        try {
            JsonReader reader = JsonCodecSupport.reader(
                    Objects.requireNonNull(bytes, "bytes"), MAX_PAYLOAD_BYTES);
            WorldItemsDocument document = readDocument(reader);
            JsonCodecSupport.requireEndDocument(reader);
            return document.toDomain();
        } catch (IOException | RuntimeException failure) {
            throw new SaveCodecException(
                    "world-items.invalid-payload",
                    "Invalid world-items payload",
                    failure);
        }
    }

    private static void writeDocument(
            JsonWriter writer,
            WorldItemsSaveSnapshot snapshot,
            List<WorldItemRestoreEntry> entries)
            throws IOException {
        writer.beginObject();
        writer.name("fixedTick").value(snapshot.fixedTick());
        writer.name("nextItemId").value(snapshot.nextItemId());
        writer.name("itemIdsExhausted").value(snapshot.itemIdsExhausted());
        writer.name("entries").beginArray();
        for (WorldItemRestoreEntry entry : entries) {
            writeEntry(writer, entry);
        }
        writer.endArray();
        writer.endObject();
    }

    private static void writeEntry(
            JsonWriter writer, WorldItemRestoreEntry entry)
            throws IOException {
        WorldItemRuntimeSnapshot runtime = entry.runtime();
        WorldItemSnapshot item = runtime.item();
        writer.beginObject();
        writer.name("id").value(item.id().value());
        writer.name("stack");
        writeStack(writer, item.stack());
        writer.name("positionX").value(item.positionX());
        writer.name("positionY").value(item.positionY());
        writer.name("positionZ").value(item.positionZ());
        writer.name("velocityX").value(item.velocityX());
        writer.name("velocityY").value(item.velocityY());
        writer.name("velocityZ").value(item.velocityZ());
        writer.name("revision").value(item.revision());
        writer.name("source");
        if (runtime.source().isPresent()) {
            writer.value(runtime.source().orElseThrow().id());
        } else {
            writer.nullValue();
        }
        writer.name("spawnTick").value(runtime.spawnTick());
        writer.name("pickupAvailableTick").value(runtime.pickupAvailableTick());
        writer.name("physicalState").value(entry.physicalState().name());
        writer.endObject();
    }

    private static void writeStack(JsonWriter writer, ItemStack stack)
            throws IOException {
        writer.beginObject();
        writer.name("itemId").value(stack.itemId().toString());
        writer.name("count").value(stack.count());
        writer.endObject();
    }

    private static WorldItemsDocument readDocument(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "world-items object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        Long fixedTick = null;
        Long nextItemId = null;
        Boolean itemIdsExhausted = null;
        List<EntryDocument> entries = null;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "fixedTick" -> fixedTick = JsonCodecSupport.readLong(reader, field);
                case "nextItemId" -> nextItemId = JsonCodecSupport.readLong(reader, field);
                case "itemIdsExhausted" ->
                        itemIdsExhausted = JsonCodecSupport.readBoolean(reader, field);
                case "entries" -> entries = readEntries(reader);
                default -> throw new IllegalArgumentException(
                        "Unknown world-items field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, ROOT_FIELDS, "world-items");
        return new WorldItemsDocument(
                fixedTick, nextItemId, itemIdsExhausted, entries);
    }

    private static List<EntryDocument> readEntries(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_ARRAY, "world-items entries array");
        reader.beginArray();
        List<EntryDocument> entries = new ArrayList<>();
        while (reader.hasNext()) {
            if (entries.size() == MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "World-item count exceeds supported capacity");
            }
            entries.add(readEntry(reader));
        }
        reader.endArray();
        return List.copyOf(entries);
    }

    private static EntryDocument readEntry(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "world-item entry object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        Long id = null;
        StackDocument stack = null;
        Double positionX = null;
        Double positionY = null;
        Double positionZ = null;
        Double velocityX = null;
        Double velocityY = null;
        Double velocityZ = null;
        Long revision = null;
        Integer source = null;
        boolean sourceRead = false;
        Long spawnTick = null;
        Long pickupAvailableTick = null;
        String physicalState = null;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "id" -> id = JsonCodecSupport.readLong(reader, field);
                case "stack" -> stack = readStack(reader);
                case "positionX" ->
                        positionX = JsonCodecSupport.readFiniteDouble(reader, field);
                case "positionY" ->
                        positionY = JsonCodecSupport.readFiniteDouble(reader, field);
                case "positionZ" ->
                        positionZ = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityX" ->
                        velocityX = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityY" ->
                        velocityY = JsonCodecSupport.readFiniteDouble(reader, field);
                case "velocityZ" ->
                        velocityZ = JsonCodecSupport.readFiniteDouble(reader, field);
                case "revision" -> revision = JsonCodecSupport.readLong(reader, field);
                case "source" -> {
                    source = readNullableSource(reader);
                    sourceRead = true;
                }
                case "spawnTick" -> spawnTick = JsonCodecSupport.readLong(reader, field);
                case "pickupAvailableTick" ->
                        pickupAvailableTick = JsonCodecSupport.readLong(reader, field);
                case "physicalState" ->
                        physicalState = JsonCodecSupport.readString(reader, field, 32);
                default -> throw new IllegalArgumentException(
                        "Unknown world-item entry field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, ENTRY_FIELDS, "world-item entry");
        if (!sourceRead) {
            throw new IllegalArgumentException("World-item source is missing");
        }
        return new EntryDocument(
                id,
                stack,
                positionX,
                positionY,
                positionZ,
                velocityX,
                velocityY,
                velocityZ,
                revision,
                source,
                spawnTick,
                pickupAvailableTick,
                physicalState);
    }

    private static StackDocument readStack(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "item stack object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        String itemId = null;
        Integer count = null;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "itemId" -> itemId = JsonCodecSupport.readString(
                        reader,
                        field,
                        JsonCodecSupport.MAX_RESOURCE_LOCATION_CODE_POINTS);
                case "count" -> count = JsonCodecSupport.readInt(reader, field);
                default -> throw new IllegalArgumentException(
                        "Unknown world-item stack field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, STACK_FIELDS, "world-item stack");
        return new StackDocument(itemId, count);
    }

    private static Integer readNullableSource(JsonReader reader)
            throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        return JsonCodecSupport.readInt(reader, "source");
    }

    private static ResourceLocation requireSupportedItemId(String encoded) {
        if (encoded.codePointCount(0, encoded.length())
                > JsonCodecSupport.MAX_RESOURCE_LOCATION_CODE_POINTS) {
            throw new IllegalArgumentException("itemId exceeds supported length");
        }
        return ResourceLocation.parse(encoded);
    }

    private static void validateEntries(List<WorldItemRestoreEntry> entries) {
        Set<WorldItemId> ids = new HashSet<>();
        for (WorldItemRestoreEntry entry : entries) {
            WorldItemSnapshot item = Objects.requireNonNull(entry, "entry")
                    .runtime()
                    .item();
            if (!ids.add(item.id())) {
                throw new IllegalArgumentException("Duplicate world-item ID");
            }
            requireSupportedItemId(item.stack().itemId().toString());
        }
    }

    private record WorldItemsDocument(
            long fixedTick,
            long nextItemId,
            boolean itemIdsExhausted,
            List<EntryDocument> entries) {
        private WorldItemsSaveSnapshot toDomain() {
            List<WorldItemRestoreEntry> domainEntries = entries.stream()
                    .map(EntryDocument::toDomain)
                    .toList();
            return new WorldItemsSaveSnapshot(
                    fixedTick, domainEntries, nextItemId, itemIdsExhausted);
        }
    }

    private record EntryDocument(
            long id,
            StackDocument stack,
            double positionX,
            double positionY,
            double positionZ,
            double velocityX,
            double velocityY,
            double velocityZ,
            long revision,
            Integer source,
            long spawnTick,
            long pickupAvailableTick,
            String physicalState) {
        private WorldItemRestoreEntry toDomain() {
            WorldItemSnapshot item = new WorldItemSnapshot(
                    new WorldItemId(id),
                    stack.toDomain(),
                    positionX,
                    positionY,
                    positionZ,
                    velocityX,
                    velocityY,
                    velocityZ,
                    revision);
            Optional<EntityRef> domainSource = source == null
                    ? Optional.empty()
                    : Optional.of(new EntityRef(source));
            WorldItemRuntimeSnapshot runtime = new WorldItemRuntimeSnapshot(
                    item,
                    domainSource,
                    spawnTick,
                    pickupAvailableTick);
            return new WorldItemRestoreEntry(
                    runtime, WorldItemPhysicalState.valueOf(physicalState));
        }
    }

    private record StackDocument(String itemId, int count) {
        private ItemStack toDomain() {
            return new ItemStack(requireSupportedItemId(itemId), count);
        }
    }
}
