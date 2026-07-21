package com.overlord.voxel;

import java.util.Random;

public class PerlinNoise {
    
    private int[] permutation;
    private int seed;
    
    public PerlinNoise(int seed) {
        this.seed = seed;
        this.permutation = generatePermutation(seed);
    }
    
    private int[] generatePermutation(int seed) {
        Random random = new Random(seed);
        int[] p = new int[256];
        
        for (int i = 0; i < 256; i++) {
            p[i] = i;
        }
        
        for (int i = 255; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int temp = p[i];
            p[i] = p[j];
            p[j] = temp;
        }
        
        int[] perm = new int[512];
        for (int i = 0; i < 256; i++) {
            perm[i] = p[i];
            perm[i + 256] = p[i];
        }
        
        return perm;
    }
    
    private double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
    
    private double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }
    
    private double grad(int hash, double x, double y) {
        int h = hash & 3;
        double u = h < 2 ? x : y;
        double v = h < 2 ? y : x;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
    
    public double noise2D(double x, double y) {
        int X = (int) Math.floor(x) & 255;
        int Y = (int) Math.floor(y) & 255;
        
        x -= Math.floor(x);
        y -= Math.floor(y);
        
        double u = fade(x);
        double v = fade(y);
        
        int A = permutation[X] + Y;
        int B = permutation[X + 1] + Y;
        
        return lerp(v,
            lerp(u, grad(permutation[A], x, y), grad(permutation[B], x - 1, y)),
            lerp(u, grad(permutation[A + 1], x, y - 1), grad(permutation[B + 1], x - 1, y - 1))
        );
    }
    
    public double octaveNoise2D(double x, double y, int octaves, double persistence) {
        double total = 0;
        double frequency = 1;
        double amplitude = 1;
        double maxValue = 0;
        
        for (int i = 0; i < octaves; i++) {
            total += noise2D(x * frequency, y * frequency) * amplitude;
            maxValue += amplitude;
            amplitude *= persistence;
            frequency *= 2;
        }
        
        return total / maxValue;
    }
    
    public int getSeed() {
        return seed;
    }
}