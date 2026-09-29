package androidx.media3.ui.libass;

import androidx.annotation.Nullable;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import java.util.function.Consumer;

/**
 * Compile-time shim for FongMi's private {@code LibassPlayerViewController}.
 *
 * <p>See {@code androidx.media3.exoplayer.libass.LibassConfiguration} for why this shim exists and
 * how to remove it.
 *
 * <p>Upstream this class owns the libass-rendered subtitle surface and the secondary subtitle view
 * inside a {@link PlayerView}. Without libass those surfaces do not exist, so the class is a no-op:
 * it records what it was told and renders nothing.
 *
 * <p><b>Subtitle styling is not lost.</b> Styling for the built-in renderer is applied by
 * {@code com.fongmi.android.tv.player.exo.ExoSubtitleController} through the application's own
 * {@code SubtitleSetting.applyStyle(SubtitleView)} — the same public media3 path the app already
 * uses elsewhere. The secondary subtitle view is the only thing genuinely unavailable, because a
 * second text renderer cannot be created without libass.
 */
public final class LibassPlayerViewController {

    private final ExoPlayer player;
    private final LibassPlaybackSession libassPlaybackSession;
    private final LibassSubtitleController libassSubtitleController;

    private @Nullable PlayerView playerView;
    private @Nullable CaptionStyleCompat styleOverride;
    private @Nullable String fontFamily;
    private @Nullable Consumer<SubtitleView> secondarySubtitleViewConfigurator;

    public LibassPlayerViewController(ExoPlayer player, LibassPlaybackSession libassPlaybackSession, LibassSubtitleController libassSubtitleController) {
        this.player = player;
        this.libassPlaybackSession = libassPlaybackSession;
        this.libassSubtitleController = libassSubtitleController;
    }

    /** The player this controller was created for; retained for parity with the real API. */
    public ExoPlayer getPlayer() {
        return player;
    }

    /** The session this controller was created for; retained for parity with the real API. */
    public LibassPlaybackSession getLibassPlaybackSession() {
        return libassPlaybackSession;
    }

    /** The controller this view controller was created for; retained for parity with the real API. */
    public LibassSubtitleController getLibassSubtitleController() {
        return libassSubtitleController;
    }

    /** Records the view it would have driven. No libass surface is attached. */
    public void bind(@Nullable PlayerView playerView) {
        this.playerView = playerView;
    }

    /** The currently bound view, if any. */
    @Nullable
    public PlayerView getPlayerView() {
        return playerView;
    }

    /** Records the forced style. Rendering is handled by the built-in subtitle path. */
    public void setStyleOverride(@Nullable CaptionStyleCompat styleOverride, @Nullable String fontFamily) {
        this.styleOverride = styleOverride;
        this.fontFamily = fontFamily;
    }

    /** The forced style, or {@code null} when embedded styles are kept. */
    @Nullable
    public CaptionStyleCompat getStyleOverride() {
        return styleOverride;
    }

    /** The requested font family, or {@code null} when none was chosen. */
    @Nullable
    public String getFontFamily() {
        return fontFamily;
    }

    /**
     * Records the secondary subtitle view configurator. It is never invoked: without libass there
     * is no secondary subtitle view to configure.
     */
    public void setSecondarySubtitleViewConfigurator(@Nullable Consumer<SubtitleView> configurator) {
        this.secondarySubtitleViewConfigurator = configurator;
    }

    /** The recorded secondary configurator, if any. */
    @Nullable
    public Consumer<SubtitleView> getSecondarySubtitleViewConfigurator() {
        return secondarySubtitleViewConfigurator;
    }

    /** Nothing to release. */
    public void close() {
        playerView = null;
        secondarySubtitleViewConfigurator = null;
    }
}
