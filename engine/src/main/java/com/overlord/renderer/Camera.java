package com.overlord.renderer;

import com.overlord.config.GameConfig;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {
    
    private Vector3f position;
    private Vector3f front;
    private Vector3f up;
    private Vector3f right;
    private Vector3f worldUp;
    
    private float yaw;
    private float pitch;
    
    private float movementSpeed;
    private float mouseSensitivity;
    
    public Camera() {
        position = new Vector3f(0.0f, 0.0f, 3.0f);
        front = new Vector3f(0.0f, 0.0f, -1.0f);
        worldUp = new Vector3f(0.0f, 1.0f, 0.0f);
        up = new Vector3f(worldUp);
        right = new Vector3f();
        
        yaw = -90.0f;
        pitch = 0.0f;
        
        movementSpeed = GameConfig.Player.MOVEMENT_SPEED;
        mouseSensitivity = 0.1f;
        
        updateVectors();
    }
    
    public Matrix4f getViewMatrix() {
        Matrix4f viewMatrix = new Matrix4f();
        Vector3f target = new Vector3f(position).add(front);
        viewMatrix.lookAt(position, target, up);
        return viewMatrix;
    }
    
    public void processKeyboard(int direction, float deltaTime) {
        float velocity = movementSpeed * deltaTime;
        
        if (direction == 0) { // FORWARD - horizontal only
            Vector3f forward = new Vector3f(front.x, 0.0f, front.z).normalize();
            position.add(forward.mul(velocity));
        }
        if (direction == 1) { // BACKWARD - horizontal only
            Vector3f backward = new Vector3f(front.x, 0.0f, front.z).normalize();
            position.sub(backward.mul(velocity));
        }
        if (direction == 2) { // LEFT
            position.sub(new Vector3f(right).mul(velocity));
        }
        if (direction == 3) { // RIGHT
            position.add(new Vector3f(right).mul(velocity));
        }
        if (direction == 4) { // UP (Space)
            position.y += velocity;
        }
        if (direction == 5) { // DOWN (Shift)
            position.y -= velocity;
        }
    }
    
    public void processMouseMovement(float xoffset, float yoffset) {
        xoffset *= mouseSensitivity;
        yoffset *= mouseSensitivity;
        
        yaw += xoffset;
        pitch += yoffset;
        
        if (pitch > 89.0f) {
            pitch = 89.0f;
        }
        if (pitch < -89.0f) {
            pitch = -89.0f;
        }
        
        updateVectors();
    }
    
    private void updateVectors() {
        front.x = (float) Math.cos(Math.toRadians(yaw)) * (float) Math.cos(Math.toRadians(pitch));
        front.y = (float) Math.sin(Math.toRadians(pitch));
        front.z = (float) Math.sin(Math.toRadians(yaw)) * (float) Math.cos(Math.toRadians(pitch));
        front.normalize();
        
        right.set(front);
        right.cross(worldUp);
        right.normalize();
        
        up.set(right);
        up.cross(front);
        up.normalize();
    }
    
    public Vector3f getPosition() {
        return position;
    }
    
    public void setPosition(Vector3f position) {
        this.position = position;
    }
    
    public float getMovementSpeed() {
        return movementSpeed;
    }
    
    public void setMovementSpeed(float movementSpeed) {
        this.movementSpeed = movementSpeed;
    }
    
    public void setPitch(float pitch) {
        this.pitch = pitch;
        if (this.pitch > 89.0f) this.pitch = 89.0f;
        if (this.pitch < -89.0f) this.pitch = -89.0f;
        updateVectors();
    }
    
    public void setYaw(float yaw) {
        this.yaw = yaw;
        updateVectors();
    }
    
    public Vector3f getForward() {
        return front;
    }
    
    public Vector3f getForward(Vector3f dest) {
        return dest.set(front);
    }
    
    public Vector3f getRight() {
        return right;
    }
    
    public Vector3f getRight(Vector3f dest) {
        return dest.set(right);
    }
    
    public float getPitch() {
        return pitch;
    }
    
    public float getYaw() {
        return yaw;
    }
}