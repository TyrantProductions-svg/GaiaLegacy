package com.gaia.tools.ui;

import java.awt.image.BufferedImage;

/** Project-owned build-time celestial geometry. No runtime texture, shader or simulation. */
final class RingedPlanetLayer {
    private static final double CENTER_X = 1040, CENTER_Y = 132, RADIUS = 100;
    private static final double COS = StrictMath.cos(StrictMath.toRadians(-15));
    private static final double SIN = StrictMath.sin(StrictMath.toRadians(-15));

    private RingedPlanetLayer() {}

    static void bakeInto(BufferedImage image) {
        // Bounded 4x subpixel integration; composite each sample before averaging.
        for (int y = 16; y < Math.min(250, image.getHeight()); y++) {
            for (int x = 810; x < Math.min(1268, image.getWidth()); x++) {
                int background = image.getRGB(x, y);
                int red = 0, green = 0, blue = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        int layer = sample(x + (sx + 0.5) / 4, y + (sy + 0.5) / 4);
                        int alpha = layer >>> 24;
                        red += ((layer >> 16 & 255) * alpha
                                + (background >> 16 & 255) * (255 - alpha) + 127) / 255;
                        green += ((layer >> 8 & 255) * alpha
                                + (background >> 8 & 255) * (255 - alpha) + 127) / 255;
                        blue += ((layer & 255) * alpha
                                + (background & 255) * (255 - alpha) + 127) / 255;
                    }
                }
                image.setRGB(x, y, 0xff000000 | (red + 8) / 16 << 16
                        | (green + 8) / 16 << 8 | (blue + 8) / 16);
            }
        }
    }

    static int sample(double x, double y) {
        int body = sphere(x, y);
        int rings = ring(x, y);
        double v = -(x - CENTER_X) * SIN + (y - CENTER_Y) * COS;
        // Geometric occlusion is separate from atmospheric opacity: the far ring
        // cannot show through the low-opacity planet as if it were a glass UI circle.
        if ((body >>> 24) != 0 && v < 0) return body;
        return over(rings, body);
    }

    static int sphere(double x, double y) {
        double nx = (x - CENTER_X) / RADIUS, ny = (y - CENTER_Y) / RADIUS;
        double rr = nx * nx + ny * ny;
        if (rr >= 1) return 0;
        double z = StrictMath.sqrt(1 - rr);
        double light = Math.max(0, -0.42 * nx - 0.36 * ny + 0.82 * z);
        double bands = 2.0 * StrictMath.sin(ny * 19 + nx * 2);
        double feather = Math.min(1, (1 - StrictMath.sqrt(rr)) * 65);
        int alpha = (int) StrictMath.round((39 + 13 * light) * feather);
        int red = (int) StrictMath.round(158 + 53 * light + bands);
        int green = (int) StrictMath.round(190 + 40 * light + bands);
        int blue = (int) StrictMath.round(210 + 30 * light + bands);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    static int ring(double x, double y) {
        double dx = x - CENTER_X, dy = y - CENTER_Y;
        double u = dx * COS + dy * SIN, v = -dx * SIN + dy * COS;
        double radius = StrictMath.sqrt(u * u / (215 * 215) + v * v / (56 * 56));
        if (radius < 0.76 || radius > 1.0) return 0;
        // Three fine bands with real gaps, not concentric screen-space circles.
        int alpha;
        if (radius < 0.82) alpha = 30;
        else if (radius < 0.84) alpha = 0;
        else if (radius < 0.94) alpha = 43;
        else if (radius < 0.967) alpha = 0;
        else alpha = 32;
        double feather = Math.min(1, Math.min((radius - 0.76) * 150, (1 - radius) * 150));
        alpha = (int) StrictMath.round(alpha * feather);
        return alpha << 24 | 0xc5dce6;
    }

    private static int over(int front, int back) {
        int fa = front >>> 24, ba = back >>> 24;
        if (fa == 0) return back;
        if (ba == 0) return front;
        int alpha = fa + (ba * (255 - fa) + 127) / 255;
        int result = alpha << 24;
        for (int shift = 0; shift <= 16; shift += 8) {
            int channel = ((front >> shift & 255) * fa
                    + ((back >> shift & 255) * ba * (255 - fa) + 127) / 255
                    + alpha / 2) / alpha;
            result |= channel << shift;
        }
        return result;
    }
}
