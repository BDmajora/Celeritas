package com.bdmajora.impetus.umbra.shaderpack.texture;

// A custom texture that has been parsed but not yet uploaded, from a texture.<stage>.<sampler>, texture.noise or
// customTexture.<name> directive
// Port of Iris's shaderpack.texture.CustomTextureData, including the raw typed definitions modern packs use for
// precomputed 3D data textures
// Construction touches neither Minecraft nor GL — it holds bytes and names only — so parsing can happen off the
// render thread. CustomTextureManager turns these into real GL textures later, on the render thread
public abstract class CustomTextureData {
    private CustomTextureData() {
    }

    // A PNG shipped inside the pack, carrying its .mcmeta blur/clamp flags alongside the bytes
    public static final class PngData extends CustomTextureData {
        private final TextureFilteringData filteringData;
        private final byte[] content;

        public PngData(TextureFilteringData filteringData, byte[] content) {
            this.filteringData = filteringData;
            this.content = content;
        }

        public TextureFilteringData getFilteringData() {
            return this.filteringData;
        }

        public byte[] getContent() {
            return this.content;
        }
    }

    // The special minecraft:dynamic/lightmap_1 location, meaning the game's live lightmap texture
    // A marker rather than a stored id because the lightmap object is recreated on resource reloads and brightness
    // changes, so it has to be resolved at bind time
    public static final class LightmapMarker extends CustomTextureData {
        @Override
        public boolean equals(Object obj) {
            return obj != null && obj.getClass() == this.getClass();
        }

        @Override
        public int hashCode() {
            return 33;
        }
    }

    // A namespace:path resource location, resolved through Minecraft's TextureManager at bind time — the resource
    // pack it comes from can change under us, so nothing is cached here
    public static final class ResourceData extends CustomTextureData {
        private final String namespace;
        private final String location;

        public ResourceData(String namespace, String location) {
            this.namespace = namespace;
            this.location = location;
        }

        // Unvalidated: a pack naming a namespace no mod provides is a skipped texture, not a load failure, and
        // that decision belongs to the caller that can log which sampler it affects
        public String getNamespace() {
            return this.namespace;
        }

        // Likewise unvalidated
        public String getLocation() {
            return this.location;
        }
    }

    // A raw binary texture, declared as e.g. `image/foo.dat TEXTURE_3D RGB16F 32 64 32 RGB HALF_FLOAT`
    // Every field is explicit because there is no container format to read them from — the file is bare pixel data
    public static final class RawData extends CustomTextureData {
        private final String textureType;
        private final String internalFormat;
        private final int width;
        private final int height;
        private final int depth;
        private final String pixelFormat;
        private final String pixelType;
        private final byte[] content;

        public RawData(String textureType, String internalFormat, int width, int height, int depth,
                       String pixelFormat, String pixelType, byte[] content) {
            this.textureType = textureType;
            this.internalFormat = internalFormat;
            this.width = width;
            this.height = height;
            this.depth = depth;
            this.pixelFormat = pixelFormat;
            this.pixelType = pixelType;
            this.content = content;
        }

        public String getTextureType() {
            return this.textureType;
        }

        public String getInternalFormat() {
            return this.internalFormat;
        }

        public int getWidth() {
            return this.width;
        }

        public int getHeight() {
            return this.height;
        }

        public int getDepth() {
            return this.depth;
        }

        public String getPixelFormat() {
            return this.pixelFormat;
        }

        public String getPixelType() {
            return this.pixelType;
        }

        public byte[] getContent() {
            return this.content;
        }
    }
}
