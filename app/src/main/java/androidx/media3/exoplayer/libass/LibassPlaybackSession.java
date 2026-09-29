package androidx.media3.exoplayer.libass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Compile-time shim for FongMi's private {@code LibassPlaybackSession}.
 *
 * <p>See {@link LibassConfiguration} for why this shim exists and how to remove it.
 *
 * <p><b>Behavioural contract:</b> {@link #isAvailable()} always returns {@code false}. That is the
 * single switch the application honours, and it is honoured in exactly the places upstream already
 * guards on it:
 *
 * <ul>
 *   <li>{@code ExoUtil.ExoRenderersFactory#buildMiscellaneousRenderers} skips the libass clock
 *       renderer.
 *   <li>{@code ExoMediaSourceFactory#createMediaSource} falls back to a plain
 *       {@code DefaultMediaSourceFactory} built on the default extractors factory.
 * </ul>
 *
 * <p>Consequently every method below is unreachable in normal playback. They exist so the
 * application compiles; they deliberately do not pretend to render anything.
 */
public final class LibassPlaybackSession {

    /** Extractors/parser pair the application would use when libass is present. */
    public static final class MediaComponents {

        public final ExtractorsFactory extractorsFactory;
        public final SubtitleParser.Factory subtitleParserFactory;

        public MediaComponents(ExtractorsFactory extractorsFactory, SubtitleParser.Factory subtitleParserFactory) {
            this.extractorsFactory = extractorsFactory;
            this.subtitleParserFactory = subtitleParserFactory;
        }
    }

    private final LibassConfiguration configuration;
    private final boolean enabled;

    public LibassPlaybackSession(LibassConfiguration configuration, boolean enabled) {
        this.configuration = configuration;
        this.enabled = enabled;
    }

    /**
     * Always {@code false}: this build has no libass native renderer. Returning {@code false} is
     * what routes the application to media3's built-in subtitle handling.
     */
    public boolean isAvailable() {
        return false;
    }

    /** The configuration this session was created with; retained for parity with the real API. */
    @NonNull
    public LibassConfiguration getConfiguration() {
        return configuration;
    }

    /** Whether the user had libass enabled in settings when this session was built. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Only reachable when {@link #isAvailable()} is true, which never happens in this build. */
    @NonNull
    public Renderer createClockRenderer() {
        throw new UnsupportedOperationException("libass is not available in this build");
    }

    /** Only reachable when {@link #isAvailable()} is true, which never happens in this build. */
    @NonNull
    public MediaComponents createMediaComponents(@NonNull MediaItem mediaItem, @NonNull ExtractorsFactory extractorsFactory) {
        throw new UnsupportedOperationException("libass is not available in this build");
    }

    /** Accepted and ignored: bottom position is applied by the built-in subtitle view instead. */
    public void setBottomPositionFraction(float bottomPositionFraction) {
    }

    /** Accepted and ignored: secondary subtitles are unavailable without libass. */
    public void setSecondaryBottomPositionFraction(float bottomPositionFraction) {
    }

    /** Accepted and ignored: font scaling is applied by the built-in subtitle view instead. */
    public void setFontScale(float fontScale, boolean applyToEmbeddedFontSizes) {
    }

    /** Accepted and ignored: there is no libass preload pipeline in this build. */
    public void setPreloadMediaItem(@Nullable MediaItem mediaItem) {
    }

    /** Nothing to release. */
    public void close() {
    }
}
