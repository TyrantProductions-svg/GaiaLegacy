package com.gaia.blocks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

public class BlockRegistry {
    
    private static final Map<Byte, Block> blocks = new HashMap<>();
    private static byte nextModId = 100;
    private static int jsonBlockCount = 0;
    
    public static final Block AIR = registerCore(BlockProperties.builder()
        .id((byte) 0)
        .name("air")
        .tolerance(0)
        .structuralIntegrity(0)
        .hardness(0)
        .transparent(true)
        .build());
    
    public static final Block GRASS = registerCore(BlockProperties.builder()
        .id((byte) 1)
        .name("grass")
        .tolerance(3.0f)
        .structuralIntegrity(50.0f)
        .hardness(0.6f)
        .flammable(false)
        .build());
    
    public static final Block DIRT = registerCore(BlockProperties.builder()
        .id((byte) 2)
        .name("dirt")
        .tolerance(2.5f)
        .structuralIntegrity(40.0f)
        .hardness(0.5f)
        .flammable(false)
        .build());
    
    public static final Block STONE = registerCore(BlockProperties.builder()
        .id((byte) 3)
        .name("stone")
        .tolerance(6.0f)
        .structuralIntegrity(80.0f)
        .hardness(1.5f)
        .flammable(false)
        .build());
    
    private static Block registerCore(BlockProperties props) {
        Block block = new Block(props);
        blocks.put(props.getId(), block);
        return block;
    }
    
    public static void init() {
        System.out.println("[BlockRegistry] Registered " + blocks.size() + " core blocks");
    }
    
    public static void loadAllFromResources() {
        try {
            URL resourceUrl = BlockRegistry.class.getClassLoader().getResource("blocks");
            if (resourceUrl == null) {
                System.out.println("[BlockRegistry] No blocks/ resource folder found");
                return;
            }
            
            Path blocksDir;
            if (resourceUrl.getProtocol().equals("file")) {
                blocksDir = Paths.get(resourceUrl.toURI());
            } else {
                System.out.println("[BlockRegistry] Cannot load blocks from JAR resources at runtime");
                return;
            }
            
            if (!Files.isDirectory(blocksDir)) {
                System.out.println("[BlockRegistry] blocks/ is not a directory");
                return;
            }
            
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            
            try (Stream<Path> paths = Files.walk(blocksDir)) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> p.toString().endsWith(".json"))
                     .forEach(path -> {
                         try {
                             String json = Files.readString(path);
                             BlockProperties props = gson.fromJson(json, BlockProperties.class);
                             registerFromProperties(props);
                             jsonBlockCount++;
                         } catch (Exception e) {
                             System.err.println("[BlockRegistry] Failed to load " + path.getFileName() + ": " + e.getMessage());
                         }
                     });
            }
            
            System.out.println("[BlockRegistry] Loaded " + blocks.size() + " blocks total (" + jsonBlockCount + " from JSON)");
            
        } catch (Exception e) {
            System.err.println("[BlockRegistry] Failed to load blocks from resources: " + e.getMessage());
        }
    }
    
    private static void registerFromProperties(BlockProperties props) {
        byte id = props.getId();
        if (id == 0) {
            id = nextModId++;
            props = BlockProperties.builder()
                .id(id)
                .name(props.getName())
                .tolerance(props.getTolerance())
                .structuralIntegrity(props.getStructuralIntegrity())
                .hardness(props.getHardness())
                .transparent(props.isTransparent())
                .lightLevel(props.getLightLevel())
                .flammable(props.isFlammable())
                .gravity(props.hasGravity())
                .blastResistance(props.getBlastResistance())
                .build();
        }
        
        Block block = new Block(props);
        blocks.put(id, block);
        System.out.println("[BlockRegistry] Registered block: " + props.getName() + " (id=" + id + ")");
    }
    
    public static void register(Block block) {
        blocks.put(block.getId(), block);
    }
    
    public static Block getBlock(byte id) {
        return blocks.getOrDefault(id, AIR);
    }
    
    public static Block getBlockByName(String name) {
        for (Block block : blocks.values()) {
            if (block.getName().equals(name)) {
                return block;
            }
        }
        return AIR;
    }
    
    public static int getBlockCount() {
        return blocks.size();
    }
}