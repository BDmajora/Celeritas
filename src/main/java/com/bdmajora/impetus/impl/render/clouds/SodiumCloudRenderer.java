package com.bdmajora.impetus.impl.render.clouds;

import com.bdmajora.impetus.umbra.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.IResource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.IOException;

// Sodium's cloud renderer on 1.12.2's fixed-function cloud geometry, omitting interior faces that cannot be seen
// rather than vanilla's two-pass depth trick, which fails under a shader pipeline
// The 1.12 numbers (12-block cells, 4-block layer, per-face tints) are kept since shader packs expect them
public final class SodiumCloudRenderer {
    private static final Logger LOGGER = LogManager.getLogger("Impetus/Clouds");
    private static final ResourceLocation CLOUDS_TEXTURES = new ResourceLocation("textures/environment/clouds.png");

    // One byte of face flags per cell, precomputed from the cloud texture so meshing never has to look at
    // neighbours again
    // The cell holds cloud; a cell without this bit emits nothing at all
    private static final int OPAQUE = 1;
    // The -Z neighbour is empty, so this cell's north wall is an exterior surface and needs a quad
    private static final int NORTH_OPEN = 1 << 1;
    // The +Z neighbour is empty
    private static final int SOUTH_OPEN = 1 << 2;
    // The -X neighbour is empty
    private static final int WEST_OPEN = 1 << 3;
    // The +X neighbour is empty
    private static final int EAST_OPEN = 1 << 4;

    // Vanilla's cell edge length in world units; the Extras cloud-scale option multiplies this and passes the
    // result to render, so the constant stays the unscaled baseline
    public static final float CELL_SIZE = 12.0F;
    // Vertical thickness of the cloud layer, also vanilla's
    private static final float THICKNESS = 4.0F;
    // Vanilla's inset applied to the far side of each cell so walls shared between adjacent cells do not land on
    // exactly the same plane and z-fight
    private static final float INSET = 9.765625E-4F;
    private static final float ALPHA = 0.8F;

    // Vanilla's fixed per-face shading, applied instead of real lighting: bottom darkest, then the Z walls, then
    // the X walls, with the top face left unmultiplied
    private static final float TINT_BOTTOM = 0.7F;
    private static final float TINT_X = 0.9F;
    private static final float TINT_Z = 0.8F;

    // Where the camera sits relative to the cloud layer (Sodium's CloudRenderer.RelativeCameraPos):
    // below means top faces are skipped, above means bottom faces are skipped, inside means every face is emitted.
    private static final int BELOW = 0;
    private static final int INSIDE = 1;
    private static final int ABOVE = 2;

    // Sodium's epsilon around the top and bottom of the layer: without it, hovering exactly at cloud height makes
    // the BELOW/INSIDE/ABOVE classification flip every frame and the culled faces pop in and out
    private static final float ORIENTATION_EPSILON = 0.125F;

    // The parsed cloud texture, rebuilt only when the resource pack changes
    private static CloudCells cachedCells;
    // Reload counter the cache was built against; -1 means "nothing cached yet", which no real counter matches
    private static int cachedTextureReloadCount = -1;

    private SodiumCloudRenderer() {
    }

    // True once the cloud texture has been read successfully, so the caller can fall back to vanilla's cloud
    // geometry before committing to a gbuffers_clouds phase.
    public static boolean isReady(Minecraft mc) {
        return getCells(mc) != null;
    }

    // Draws the whole cloud layer in one call. cellSize scales the cell rather than the finished mesh, which keeps
    // the cloud texture at one texel per cell instead of blurring at large scales (Extras cloud-scale option).
    // Returns false if the cloud texture could not be read and the caller should fall back to vanilla's renderer.
    public static boolean render(Minecraft mc, WorldClient world, TextureManager textureManager, int cloudTickCounter,
            float partialTicks, int pass, double cameraX, double cameraY, double cameraZ, boolean fancy,
            int radiusCells, float cloudHeight, float cellSize) {
        CloudCells cells = getCells(mc);
        if (cells == null) {
            return false;
        }

        // Vanilla's cell-space camera position. The 2048-cell wrap matters: without it the texcoords derived from the
        // absolute cell index lose all sub-texel precision a few hundred thousand blocks out.
        double cloudTime = (double) ((float) cloudTickCounter + partialTicks);
        double gridX = (cameraX + cloudTime * 0.029999999329447746D) / cellSize;
        double gridZ = cameraZ / cellSize + 0.33000001311302185D;
        gridX -= (double) (MathHelper.floor(gridX / 2048.0D) * 2048);
        gridZ -= (double) (MathHelper.floor(gridZ / 2048.0D) * 2048);

        int cameraCellX = MathHelper.floor(gridX);
        int cameraCellZ = MathHelper.floor(gridZ);
        float subCellX = (float) (gridX - (double) cameraCellX);
        float subCellZ = (float) (gridZ - (double) cameraCellZ);

        // Camera-relative altitude of the underside of the layer, so "camera below the layer" is bottomY > 0.
        // Sodium tests cameraY <= minY + eps / cameraY >= maxY - eps; in these camera-relative terms that is
        // bottomY >= -eps and bottomY + thickness <= eps.
        float bottomY = cloudHeight - (float) cameraY + 0.33F;
        int cameraSide;
        if (bottomY >= -ORIENTATION_EPSILON) {
            cameraSide = BELOW;
        } else if (bottomY + THICKNESS <= ORIENTATION_EPSILON) {
            cameraSide = ABOVE;
        } else {
            cameraSide = INSIDE;
        }

        Vec3d cloudColour = world.getCloudColour(partialTicks);
        float red = (float) cloudColour.x;
        float green = (float) cloudColour.y;
        float blue = (float) cloudColour.z;

        // Anaglyph: vanilla desaturates per eye. pass 2 is the ordinary single-eye render.
        if (pass != 2) {
            float grey = (red * 30.0F + green * 59.0F + blue * 11.0F) / 100.0F;
            float warm = (red * 30.0F + green * 70.0F) / 100.0F;
            float cool = (red * 30.0F + blue * 70.0F) / 100.0F;
            red = grey;
            green = warm;
            blue = cool;
        }

        textureManager.bindTexture(CLOUDS_TEXTURES);
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);

        GlStateManager.pushMatrix();
        GlStateManager.scale(cellSize, 1.0F, cellSize);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        // A normal is emitted even on the fast path: vanilla's fast clouds use POSITION_TEX_COLOR and leave gl_Normal
        // at whatever the previous draw set, which gbuffers_clouds then shades against.
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);

        int radius = Math.max(1, radiusCells);
        int radiusSquared = radius * radius;

        for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                // Sodium meshes a disc, not a square: the corners of a square are past the far plane anyway.
                if (offsetX * offsetX + offsetZ * offsetZ > radiusSquared) {
                    continue;
                }

                int cellX = cameraCellX + offsetX;
                int cellZ = cameraCellZ + offsetZ;
                int flags = cells.get(cellX, cellZ);
                if ((flags & OPAQUE) == 0) {
                    continue;
                }

                if (!fancy) {
                    emitFastCell(buffer, cells, cellX, cellZ, offsetX, offsetZ, subCellX, subCellZ, bottomY,
                            cameraSide, red, green, blue);
                    continue;
                }

                // Sodium's interior geometry: standing in the layer, the faces of the cell around you all point away
                // from you, so that cell is turned inside out instead. Replacing the exterior faces rather than adding
                // to them (Sodium distinguishes the two with a shader flag) keeps the two sets from being coincident.
                boolean interior = cameraSide == INSIDE && Math.abs(offsetX) + Math.abs(offsetZ) <= 1;
                emitCell(buffer, cells, cellX, cellZ, offsetX, offsetZ, subCellX, subCellZ, bottomY, cameraSide,
                        flags, interior, red, green, blue);
            }
        }

        tessellator.draw();

        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        return true;
    }

    // Sodium's fast path: one face per cell, no walls or second layer, since fast clouds are just a flat sheet.
    private static void emitFastCell(BufferBuilder buffer, CloudCells cells, int cellX, int cellZ, int offsetX,
            int offsetZ, float subCellX, float subCellZ, float bottomY, int cameraSide, float red, float green,
            float blue) {
        float x0 = (float) offsetX - subCellX;
        float x1 = x0 + 1.0F;
        float z0 = (float) offsetZ - subCellZ;
        float z1 = z0 + 1.0F;
        float u0 = cells.u(cellX);
        float u1 = cells.u(cellX + 1);
        float v0 = cells.v(cellZ);
        float v1 = cells.v(cellZ + 1);
        // Culling is off, so the sheet is visible from both sides; the normal is pointed at the camera so packs shade
        // it as the surface the player is actually looking at.
        float normalY = cameraSide == ABOVE ? 1.0F : -1.0F;

        vertex(buffer, x0, bottomY, z1, u0, v1, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x1, bottomY, z1, u1, v1, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x1, bottomY, z0, u1, v0, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x0, bottomY, z0, u0, v0, red, green, blue, 0.0F, normalY, 0.0F);
    }

    // Sodium's emitCellGeometryExterior/emitCellGeometryInterior. The interior form is the same cell turned inside
    // out: every face present, every normal reversed.
    private static void emitCell(BufferBuilder buffer, CloudCells cells, int cellX, int cellZ, int offsetX,
            int offsetZ, float subCellX, float subCellZ, float bottomY, int cameraSide, int flags, boolean interior,
            float red, float green, float blue) {
        float x0 = (float) offsetX - subCellX;
        float x1 = x0 + 1.0F;
        float z0 = (float) offsetZ - subCellZ;
        float z1 = z0 + 1.0F;
        float y0 = bottomY;
        float y1 = bottomY + THICKNESS;
        float u0 = cells.u(cellX);
        float u1 = cells.u(cellX + 1);
        float uc = cells.uCentre(cellX);
        float v0 = cells.v(cellZ);
        float v1 = cells.v(cellZ + 1);
        float vc = cells.vCentre(cellZ);
        // Flips every normal, so an interior face is shaded as the surface seen from inside the cell.
        float facing = interior ? -1.0F : 1.0F;

        if (interior || cameraSide != BELOW) {
            horizontal(buffer, x0, x1, y1 - INSET, z0, z1, u0, u1, v0, v1, red, green, blue, facing);
        }

        if (interior || cameraSide != ABOVE) {
            horizontal(buffer, x0, x1, y0, z0, z1, u0, u1, v0, v1,
                    red * TINT_BOTTOM, green * TINT_BOTTOM, blue * TINT_BOTTOM, -facing);
        }

        // A wall is only drawn where the cloud actually ends, and only on the side the camera can see it from: a cell
        // south of you (offsetZ > 0) can only ever show you its north face.
        if (interior || ((flags & NORTH_OPEN) != 0 && offsetZ > 0)) {
            zWall(buffer, x0, x1, y0, y1, z0, u0, u1, vc, red, green, blue, -facing);
        }

        if (interior || ((flags & SOUTH_OPEN) != 0 && offsetZ < 0)) {
            zWall(buffer, x0, x1, y0, y1, z1 - INSET, u0, u1, vc, red, green, blue, facing);
        }

        if (interior || ((flags & WEST_OPEN) != 0 && offsetX > 0)) {
            xWall(buffer, x0, y0, y1, z0, z1, uc, v0, v1, red, green, blue, -facing);
        }

        if (interior || ((flags & EAST_OPEN) != 0 && offsetX < 0)) {
            xWall(buffer, x1 - INSET, y0, y1, z0, z1, uc, v0, v1, red, green, blue, facing);
        }
    }

    // Face emitters. Vanilla winds all six faces the same way and tells them apart purely by the normal — it can,
    // because cloud rendering runs with culling off.

    private static void horizontal(BufferBuilder buffer, float x0, float x1, float y, float z0, float z1, float u0,
            float u1, float v0, float v1, float red, float green, float blue, float normalY) {
        vertex(buffer, x0, y, z1, u0, v1, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x1, y, z1, u1, v1, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x1, y, z0, u1, v0, red, green, blue, 0.0F, normalY, 0.0F);
        vertex(buffer, x0, y, z0, u0, v0, red, green, blue, 0.0F, normalY, 0.0F);
    }

    // A wall in the XY plane. It samples the cell's own texture row, so it takes that cell's colour.
    private static void zWall(BufferBuilder buffer, float x0, float x1, float y0, float y1, float z, float u0,
            float u1, float vc, float red, float green, float blue, float normalZ) {
        float r = red * TINT_Z;
        float g = green * TINT_Z;
        float b = blue * TINT_Z;
        vertex(buffer, x0, y1, z, u0, vc, r, g, b, 0.0F, 0.0F, normalZ);
        vertex(buffer, x1, y1, z, u1, vc, r, g, b, 0.0F, 0.0F, normalZ);
        vertex(buffer, x1, y0, z, u1, vc, r, g, b, 0.0F, 0.0F, normalZ);
        vertex(buffer, x0, y0, z, u0, vc, r, g, b, 0.0F, 0.0F, normalZ);
    }

    // A wall in the ZY plane, sampling the cell's own texture column.
    private static void xWall(BufferBuilder buffer, float x, float y0, float y1, float z0, float z1, float uc,
            float v0, float v1, float red, float green, float blue, float normalX) {
        float r = red * TINT_X;
        float g = green * TINT_X;
        float b = blue * TINT_X;
        vertex(buffer, x, y0, z1, uc, v1, r, g, b, normalX, 0.0F, 0.0F);
        vertex(buffer, x, y1, z1, uc, v1, r, g, b, normalX, 0.0F, 0.0F);
        vertex(buffer, x, y1, z0, uc, v0, r, g, b, normalX, 0.0F, 0.0F);
        vertex(buffer, x, y0, z0, uc, v0, r, g, b, normalX, 0.0F, 0.0F);
    }

    private static void vertex(BufferBuilder buffer, float x, float y, float z, float u, float v, float red,
            float green, float blue, float normalX, float normalY, float normalZ) {
        buffer.pos((double) x, (double) y, (double) z)
                .tex((double) u, (double) v)
                .color(red, green, blue, ALPHA)
                .normal(normalX, normalY, normalZ)
                .endVertex();
    }

    // Loads the cloud texture into a cell grid once, rebuilt on resource reload
    private static CloudCells getCells(Minecraft mc) {
        int textureReloadCount = CapturedRenderingState.INSTANCE.getTextureReloadCount();
        if (cachedTextureReloadCount == textureReloadCount) {
            return cachedCells;
        }

        // Cached against the reload counter either way, so a pack with an unreadable cloud texture logs once per
        // resource reload rather than once per frame.
        cachedTextureReloadCount = textureReloadCount;
        cachedCells = null;

        try (IResource resource = mc.getResourceManager().getResource(CLOUDS_TEXTURES)) {
            BufferedImage image = TextureUtil.readBufferedImage(resource.getInputStream());
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IOException("cloud texture is empty");
            }
            cachedCells = new CloudCells(image);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}; falling back to vanilla cloud geometry", CLOUDS_TEXTURES, e);
        }

        return cachedCells;
    }

    // The cloud texture reduced to per-cell occupancy plus which of its four sides border air.
    // Sodium's CloudRenderer.TextureData; replaces its isNorthEmpty/isSouthEmpty/isWestEmpty/isEastEmpty predicates.
    private static final class CloudCells {
        private final byte[] flags;
        private final int width;
        private final int height;

        private CloudCells(BufferedImage image) {
            this.width = image.getWidth();
            this.height = image.getHeight();
            this.flags = new byte[this.width * this.height];

            int[] pixels = new int[this.width * this.height];
            image.getRGB(0, 0, this.width, this.height, pixels, 0, this.width);

            for (int z = 0; z < this.height; z++) {
                for (int x = 0; x < this.width; x++) {
                    if (!isOpaque(pixels, x, z)) {
                        continue;
                    }

                    int cell = OPAQUE;
                    if (!isOpaque(pixels, x, z - 1)) {
                        cell |= NORTH_OPEN;
                    }
                    if (!isOpaque(pixels, x, z + 1)) {
                        cell |= SOUTH_OPEN;
                    }
                    if (!isOpaque(pixels, x - 1, z)) {
                        cell |= WEST_OPEN;
                    }
                    if (!isOpaque(pixels, x + 1, z)) {
                        cell |= EAST_OPEN;
                    }
                    this.flags[index(x, z)] = (byte) cell;
                }
            }
        }

        // Cell value with wrap-around
        private int get(int x, int z) {
            return this.flags[index(x, z)];
        }

        // Texture coordinate of a cell boundary. The texture repeats, so an unwrapped cell index is fine here.
        private float u(int cellX) {
            return (float) cellX / (float) this.width;
        }

        // Texture v coordinate for a cell edge
        private float v(int cellZ) {
            return (float) cellZ / (float) this.height;
        }

        // Walls sample the middle of the cell's texel, as vanilla does, so they take the cell's own colour.
        private float uCentre(int cellX) {
            return ((float) cellX + 0.5F) / (float) this.width;
        }

        // Texture v coordinate for a cell centre
        private float vCentre(int cellZ) {
            return ((float) cellZ + 0.5F) / (float) this.height;
        }

        // Sodium's threshold, inverted (isTransparent = alpha < 10). Vanilla's clouds.png stores gaps as alpha 1,
        // not alpha 0, so a plain != 0 test would classify the whole texture as cloud.
        private boolean isOpaque(int[] pixels, int x, int z) {
            return ((pixels[index(x, z)] >>> 24) & 255) >= 10;
        }

        // Wrapped index into the cell array
        private int index(int x, int z) {
            // Row-major, matching BufferedImage#getRGB's scan order.
            return Math.floorMod(z, this.height) * this.width + Math.floorMod(x, this.width);
        }
    }
}
