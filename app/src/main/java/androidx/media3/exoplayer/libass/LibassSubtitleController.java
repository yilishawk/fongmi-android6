package androidx.media3.exoplayer.libass;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.trackselection.TrackSelector;

import java.util.Collections;
import java.util.List;

/**
 * Compile-time shim for FongMi's private {@code LibassSubtitleController}.
 *
 * <p>See {@link LibassConfiguration} for why this shim exists and how to remove it.
 *
 * <p>This controller is the handle the application uses to drive the <i>secondary</i> (dual)
 * subtitle track — a FongMi feature layered on libass. Without libass there is no second text
 * renderer, so the secondary track cannot exist at all. The getters therefore report an empty
 * state, which is precisely the state the UI already handles for "no secondary track available"
 * (compare {@code PlayerEngine.SecondarySubtitleState.EMPTY}).
 *
 * <p>The primary subtitle track is unaffected: it is handled entirely by media3's built-in text
 * renderer, and the application reads the primary selection from the player itself.
 */
public final class LibassSubtitleController {

    public LibassSubtitleController(@NonNull ExoPlayer player, @NonNull LibassPlaybackSession libassPlaybackSession, @NonNull TrackSelector.Factory trackSelectorFactory, @NonNull TextOutput secondaryTextOutput) {
    }

    /** No libass means no secondary text renderer, so there is never a primary override here. */
    @Nullable
    public TrackSelectionOverride getPrimaryTextTrackSelectionOverride() {
        return null;
    }

    /** No secondary track can be selected in this build. */
    @Nullable
    public TrackSelectionOverride getSecondaryTextTrackSelectionOverride() {
        return null;
    }

    /** No secondary track can be selected in this build. */
    @NonNull
    public List<TrackSelectionOverride> getSecondaryTextTrackSelectionOverrides() {
        return Collections.emptyList();
    }

    /** Reported as suppressed so the UI hides the secondary-track controls. */
    public boolean isSecondaryTextTrackSuppressed() {
        return true;
    }

    /** Accepted and ignored: there is no secondary track to select. */
    public void setSecondaryTextTrackSelectionOverride(@Nullable TrackSelectionOverride selection) {
    }

    /** Accepted and ignored: there is no secondary track to auto-select. */
    public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {
    }

    /** Nothing to release. */
    public void close() {
    }
}
