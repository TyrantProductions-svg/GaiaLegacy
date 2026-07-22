package com.gaia;

import com.gaia.GameBootstrap.GameContext;

public class GaiaMain {
    
    public static void main(String[] args) {
        GameContext context = GameBootstrap.bootstrap();
        GameLoop gameLoop = new GameLoop(context);
        gameLoop.run();
    }
}