package androidx.media3.exoplayer.text;

import androidx.media3.common.text.CueGroup;

/**
 * Compile-time shim for FongMi's private {@code SecondaryTextOutput}.
 *
 * <p>See {@code androidx.media3.exoplayer.libass.LibassConfiguration} for why this shim exists and
 * how to remove it.
 *
 * <p>This is the sink the secondary text renderer writes cues into. Without libass there is no
 * secondary renderer and no second subtitle view, so nothing is ever delivered here. The
 * implementation is intentionally a no-op rather than pretending to draw.
 *
 * <p>{@code TextOutput} declares exactly one abstract method — {@code onCues(CueGroup)} — which is
 * all this class must implement.
 */
public final class SecondaryTextOutput implements TextOutput {

    public SecondaryTextOutput() {
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        // No secondary subtitle view exists in this build; cues are intentionally dropped.
    }
}
