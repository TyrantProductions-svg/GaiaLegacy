package com.gaia.save.codec;

import com.gaia.save.format.SaveSectionCodec;
import com.gaia.save.format.SaveSectionId;
import com.gaia.save.snapshot.InventorySaveSnapshot;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.overlord.assets.ResourceLocation;
import com.overlord.interaction.api.EntityRef;
import com.overlord.inventory.api.BodySlot;
import com.overlord.inventory.api.ItemStack;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic JSON codec for canonical three-slot body inventory state. */
public final class InventorySectionCodec
        implements SaveSectionCodec<InventorySaveSnapshot> {
    private static final int CODEC_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of(
            "owner",
            "revision",
            "activeSlot",
            "twoHandedHandsOccupied",
            "slots");
    private static final Set<String> SLOT_FIELDS = Set.of("slot", "stack");
    private static final Set<String> STACK_FIELDS = Set.of("itemId", "count");

    @Override
    public SaveSectionId sectionId() {
        return SaveSectionId.INVENTORY;
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
    public byte[] encode(InventorySaveSnapshot snapshot) {
        try {
            InventorySaveSnapshot value =
                    Objects.requireNonNull(snapshot, "snapshot");
            validateShape(value.stacks(), value.twoHandedHandsOccupied());
            for (ItemStack stack : value.stacks().values()) {
                requireSupportedItemId(stack.itemId().toString());
            }
            return JsonCodecSupport.write(writer -> writeDocument(writer, value));
        } catch (RuntimeException failure) {
            throw new SaveCodecException(
                    "inventory.invalid-snapshot",
                    "Invalid inventory snapshot",
                    failure);
        }
    }

    @Override
    public InventorySaveSnapshot decode(byte[] bytes) {
        try {
            JsonReader reader = JsonCodecSupport.reader(
                    Objects.requireNonNull(bytes, "bytes"), MAX_PAYLOAD_BYTES);
            InventoryDocument document = readDocument(reader);
            JsonCodecSupport.requireEndDocument(reader);
            return document.toDomain();
        } catch (IOException | RuntimeException failure) {
            throw new SaveCodecException(
                    "inventory.invalid-payload",
                    "Invalid inventory payload",
                    failure);
        }
    }

    private static void writeDocument(
            JsonWriter writer, InventorySaveSnapshot snapshot)
            throws IOException {
        writer.beginObject();
        writer.name("owner").value(snapshot.owner().id());
        writer.name("revision").value(snapshot.revision());
        writer.name("activeSlot").value(snapshot.activeSlot().name());
        writer.name("twoHandedHandsOccupied")
                .value(snapshot.twoHandedHandsOccupied());
        writer.name("slots").beginArray();
        for (BodySlot slot : BodySlot.values()) {
            writer.beginObject();
            writer.name("slot").value(slot.name());
            writer.name("stack");
            ItemStack stack = snapshot.stacks().get(slot);
            if (stack == null) {
                writer.nullValue();
            } else {
                writeStack(writer, stack);
            }
            writer.endObject();
        }
        writer.endArray();
        writer.endObject();
    }

    private static void writeStack(JsonWriter writer, ItemStack stack)
            throws IOException {
        writer.beginObject();
        writer.name("itemId").value(stack.itemId().toString());
        writer.name("count").value(stack.count());
        writer.endObject();
    }

    private static InventoryDocument readDocument(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "inventory object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        Integer owner = null;
        Long revision = null;
        String activeSlot = null;
        Boolean twoHandedHandsOccupied = null;
        EnumMap<BodySlot, ItemDocument> slots = null;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "owner" -> owner = JsonCodecSupport.readInt(reader, field);
                case "revision" -> revision = JsonCodecSupport.readLong(reader, field);
                case "activeSlot" -> activeSlot = JsonCodecSupport.readString(reader, field, 32);
                case "twoHandedHandsOccupied" ->
                        twoHandedHandsOccupied = JsonCodecSupport.readBoolean(reader, field);
                case "slots" -> slots = readSlots(reader);
                default -> throw new IllegalArgumentException(
                        "Unknown inventory field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, ROOT_FIELDS, "inventory");
        return new InventoryDocument(
                owner, revision, activeSlot, twoHandedHandsOccupied, slots);
    }

    private static EnumMap<BodySlot, ItemDocument> readSlots(JsonReader reader)
            throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_ARRAY, "slots array");
        reader.beginArray();
        EnumMap<BodySlot, ItemDocument> slots = new EnumMap<>(BodySlot.class);
        int count = 0;
        while (reader.hasNext()) {
            if (++count > BodySlot.values().length) {
                throw new IllegalArgumentException("Inventory contains too many slots");
            }
            SlotDocument slot = readSlot(reader);
            if (slots.containsKey(slot.slot())) {
                throw new IllegalArgumentException("Duplicate inventory slot");
            }
            slots.put(slot.slot(), slot.stack());
        }
        reader.endArray();
        if (slots.size() != BodySlot.values().length) {
            throw new IllegalArgumentException("Inventory must contain every physical slot");
        }
        return slots;
    }

    private static SlotDocument readSlot(JsonReader reader) throws IOException {
        JsonCodecSupport.requireToken(reader, JsonToken.BEGIN_OBJECT, "slot object");
        reader.beginObject();
        Set<String> seen = JsonCodecSupport.newFieldSet();
        BodySlot slot = null;
        ItemDocument stack = null;
        boolean stackRead = false;
        while (reader.hasNext()) {
            String field = JsonCodecSupport.nextUniqueField(reader, seen);
            switch (field) {
                case "slot" -> slot = readBodySlot(reader, field);
                case "stack" -> {
                    stack = readNullableStack(reader);
                    stackRead = true;
                }
                default -> throw new IllegalArgumentException(
                        "Unknown inventory slot field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, SLOT_FIELDS, "inventory slot");
        if (!stackRead) {
            throw new IllegalArgumentException("Inventory slot stack is missing");
        }
        return new SlotDocument(slot, stack);
    }

    private static ItemDocument readNullableStack(JsonReader reader)
            throws IOException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
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
                        "Unknown item stack field: " + field);
            }
        }
        reader.endObject();
        JsonCodecSupport.requireFields(seen, STACK_FIELDS, "item stack");
        return new ItemDocument(itemId, count);
    }

    private static BodySlot readBodySlot(JsonReader reader, String field)
            throws IOException {
        String encoded = JsonCodecSupport.readString(reader, field, 32);
        try {
            return BodySlot.valueOf(encoded);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unknown BodySlot: " + encoded, failure);
        }
    }

    private static ResourceLocation requireSupportedItemId(String encoded) {
        if (encoded.codePointCount(0, encoded.length())
                > JsonCodecSupport.MAX_RESOURCE_LOCATION_CODE_POINTS) {
            throw new IllegalArgumentException("itemId exceeds supported length");
        }
        return ResourceLocation.parse(encoded);
    }

    private static void validateShape(
            Map<BodySlot, ItemStack> stacks, boolean twoHanded) {
        if (stacks.size() > BodySlot.values().length) {
            throw new IllegalArgumentException("Inventory contains too many direct slots");
        }
        if (twoHanded
                && (stacks.get(BodySlot.LEFT_HAND) == null
                        || stacks.get(BodySlot.RIGHT_HAND) != null)) {
            throw new IllegalArgumentException(
                    "Two-handed inventory requires one left-hand anchor");
        }
    }

    private record InventoryDocument(
            int owner,
            long revision,
            String activeSlot,
            boolean twoHandedHandsOccupied,
            EnumMap<BodySlot, ItemDocument> slots) {
        private InventorySaveSnapshot toDomain() {
            EnumMap<BodySlot, ItemStack> stacks = new EnumMap<>(BodySlot.class);
            for (BodySlot slot : BodySlot.values()) {
                ItemDocument document = slots.get(slot);
                if (document != null) {
                    stacks.put(slot, document.toDomain());
                }
            }
            validateShape(stacks, twoHandedHandsOccupied);
            return new InventorySaveSnapshot(
                    new EntityRef(owner),
                    stacks,
                    BodySlot.valueOf(activeSlot),
                    twoHandedHandsOccupied,
                    revision);
        }
    }

    private record SlotDocument(BodySlot slot, ItemDocument stack) {
    }

    private record ItemDocument(String itemId, int count) {
        private ItemStack toDomain() {
            return new ItemStack(requireSupportedItemId(itemId), count);
        }
    }
}
