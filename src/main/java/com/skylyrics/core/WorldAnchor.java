package com.skylyrics.core;

/**
 * A fixed spot in the world for one lyric line, plus the rotation that makes the text plane face the place the
 * player was standing when the line appeared. Pure math: no Minecraft classes.
 *
 * Rotation order for rendering (PoseStack, local frame): rotate about Y by yawRad, then about X by pitchRad,
 * then scale(s, -s, s). Text is then readable from the side the viewer stood on.
 */
public record WorldAnchor(double x, double y, double z, float yawRad, float pitchRad) {

    /**
     * @param camX,camY,camZ   the viewer's eye position when the line appeared
     * @param viewYawDeg       the viewer's yaw (Minecraft convention: 0 = south, 90 = west)
     * @param yawOffsetDeg     how far left/right of the view direction to put the text (positive = towards +yaw)
     * @param degreesAbove     how far above the horizon to put the text
     * @param distance         distance from the viewer in blocks
     */
    public static WorldAnchor create(double camX, double camY, double camZ, double viewYawDeg,
                                     double yawOffsetDeg, double degreesAbove, double distance) {
        double yaw = Math.toRadians(viewYawDeg + yawOffsetDeg);
        double up = Math.toRadians(degreesAbove);
        double dx = -Math.sin(yaw) * Math.cos(up);
        double dy = Math.sin(up);
        double dz = Math.cos(yaw) * Math.cos(up);

        double px = camX + dx * distance;
        double py = camY + dy * distance;
        double pz = camZ + dz * distance;

        // Vector from the text back to the viewer.
        double vx = camX - px;
        double vy = camY - py;
        double vz = camZ - pz;
        double horizontal = Math.hypot(vx, vz);
        float facingYaw = (float) Math.atan2(vx, vz);
        float facingPitch = (float) Math.atan2(-vy, horizontal);
        return new WorldAnchor(px, py, pz, facingYaw, facingPitch);
    }
}
