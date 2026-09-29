package androidx.media3.exoplayer.libass;

import androidx.annotation.Nullable;

/**
 * Compile-time shim for FongMi's private {@code LibassConfiguration}.
 *
 * <p>FongMi's media3 build adds an optional libass (ASS/SSA) subtitle renderer that is not part of
 * any published artifact — see {@code app/src/main/java/androidx/media3/exoplayer/libass/README}
 * for the full evidence trail. This project therefore ships the API surface the application code
 * references, so {@code com.fongmi.*} sources stay byte-identical to upstream.
 *
 * <p>This class only carries configuration; nothing in it performs work. It is never consulted
 * because {@link LibassPlaybackSession#isAvailable()} reports {@code false}, which makes the
 * application take its built-in media3 subtitle path instead.
 *
 * <p>To restore real libass support: drop the genuine {@code lib-*.aar} files into
 * {@code app/libs} and delete the whole {@code androidx/media3/} shim tree under
 * {@code app/src/main/java/}. No application source has to change.
 */
public final class LibassConfiguration {

    public final @Nullable String fontConfigPath;
    public final @Nullable String fontsDirectory;
    public final @Nullable String defaultFontFamily;
    public final int maximumRenderPixels;
    public final int maximumGlyphCount;
    public final int maximumBitmapCacheSizeMb;

    private LibassConfiguration(Builder builder) {
        this.fontConfigPath = builder.fontConfigPath;
        this.fontsDirectory = builder.fontsDirectory;
        this.defaultFontFamily = builder.defaultFontFamily;
        this.maximumRenderPixels = builder.maximumRenderPixels;
        this.maximumGlyphCount = builder.maximumGlyphCount;
        this.maximumBitmapCacheSizeMb = builder.maximumBitmapCacheSizeMb;
    }

    public static final class Builder {

        private @Nullable String fontConfigPath;
        private @Nullable String fontsDirectory;
        private @Nullable String defaultFontFamily;
        private int maximumRenderPixels;
        private int maximumGlyphCount;
        private int maximumBitmapCacheSizeMb;

        public Builder setFontConfig(@Nullable String fontConfigPath) {
            this.fontConfigPath = fontConfigPath;
            return this;
        }

        public Builder setFontsDirectory(@Nullable String fontsDirectory) {
            this.fontsDirectory = fontsDirectory;
            return this;
        }

        public Builder setDefaultFontFamily(@Nullable String defaultFontFamily) {
            this.defaultFontFamily = defaultFontFamily;
            return this;
        }

        public Builder setMaximumRenderPixels(int maximumRenderPixels) {
            this.maximumRenderPixels = maximumRenderPixels;
            return this;
        }

        public Builder setMaximumGlyphCount(int maximumGlyphCount) {
            this.maximumGlyphCount = maximumGlyphCount;
            return this;
        }

        public Builder setMaximumBitmapCacheSizeMb(int maximumBitmapCacheSizeMb) {
            this.maximumBitmapCacheSizeMb = maximumBitmapCacheSizeMb;
            return this;
        }

        public LibassConfiguration build() {
            return new LibassConfiguration(this);
        }
    }
}
