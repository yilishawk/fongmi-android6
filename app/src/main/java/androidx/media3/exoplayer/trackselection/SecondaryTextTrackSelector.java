package androidx.media3.exoplayer.trackselection;

import android.content.Context;

import androidx.annotation.NonNull;

/**
 * Compile-time shim for FongMi's private {@code SecondaryTextTrackSelector}.
 *
 * <p>See {@code androidx.media3.exoplayer.libass.LibassConfiguration} for why this shim exists and
 * how to remove it.
 *
 * <p>Upstream wraps the decode-aware track selector so that a <i>second</i> text track can be
 * selected alongside the primary one. Without libass there is no second text renderer, so the
 * wrapper has nothing to add — but the primary selection must keep working exactly as before.
 *
 * <p>{@link Factory} therefore simply <b>delegates</b> to the selector factory it is given. The
 * application keeps its real {@code DecodeTrackSelector} (hardware/software decode preferences,
 * preferred languages, tunneling), so playback behaviour is unchanged.
 */
public final class SecondaryTextTrackSelector {

    private SecondaryTextTrackSelector() {
    }

    /** Delegating factory: preserves the wrapped selector factory's behaviour verbatim. */
    public static final class Factory implements TrackSelector.Factory {

        private final TrackSelector.Factory delegate;

        public Factory(TrackSelector.Factory delegate) {
            this.delegate = delegate;
        }

        @NonNull
        @Override
        public TrackSelector createTrackSelector(@NonNull Context context) {
            return delegate.createTrackSelector(context);
        }
    }
}
