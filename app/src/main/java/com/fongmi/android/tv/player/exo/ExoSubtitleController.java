package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.exoplayer.libass.LibassPlaybackSession;
import androidx.media3.exoplayer.libass.LibassSubtitleController;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;
import androidx.media3.ui.libass.LibassPlayerViewController;

import com.fongmi.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.fongmi.android.tv.setting.SubtitleSetting;

final class ExoSubtitleController {

    private final LibassSubtitleController libassSubtitleController;
    private final LibassPlaybackSession libassPlaybackSession;
    private final LibassPlayerViewController playerViewController;

    @Nullable
    private PlayerView playerView;

    ExoSubtitleController(ExoPlayerSession session) {
        libassPlaybackSession = session.libassPlaybackSession();
        libassSubtitleController = session.libassSubtitleController();
        playerViewController = new LibassPlayerViewController(session.player(), libassPlaybackSession, libassSubtitleController);
        applySubtitleStyle();
        applySecondarySubtitleMode();
    }

    void release() {
        playerViewController.close();
    }

    void bindPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        playerViewController.bind(playerView);
        applyBuiltInSubtitleStyle();
    }

    void applySubtitleStyle() {
        libassPlaybackSession.setBottomPositionFraction(SubtitleSetting.getPosition() / 100.0f);
        libassPlaybackSession.setSecondaryBottomPositionFraction(getSecondaryBottomPositionFraction());
        libassPlaybackSession.setFontScale(SubtitleSetting.isScaleApplied() ? SubtitleSetting.getAppliedScale() : 1.0f, true);
        playerViewController.setStyleOverride(SubtitleSetting.isStyleForced() ? SubtitleSetting.getStyle() : null, SubtitleSetting.getFontFamily());
        playerViewController.setSecondarySubtitleViewConfigurator(this::applySecondarySubtitleStyle);
        applyBuiltInSubtitleStyle();
    }

    /**
     * Applies the subtitle style to media3's built-in subtitle view.
     *
     * <p>When libass is present it owns the subtitle surface and this method is a no-op. Without it
     * — the case for this build — the primary subtitle is rendered by media3's built-in text
     * renderer, so the user's style/scale/position settings have to be pushed onto the bound
     * {@link PlayerView}'s {@code SubtitleView}. {@code SubtitleSetting.applyStyle} is the
     * application's own entry point for exactly that path, so no styling logic is duplicated here.
     *
     * <p>Restoring a real libass AAR flips {@code isAvailable()} back to {@code true} and this
     * method disables itself automatically.
     */
    private void applyBuiltInSubtitleStyle() {
        if (libassPlaybackSession.isAvailable()) return;
        SubtitleSetting.applyStyle(playerView == null ? null : playerView.getSubtitleView());
    }

    SecondarySubtitleState getSecondarySubtitleState() {
        return new SecondarySubtitleState(libassSubtitleController.getPrimaryTextTrackSelectionOverride(), libassSubtitleController.getSecondaryTextTrackSelectionOverride(), libassSubtitleController.getSecondaryTextTrackSelectionOverrides(), libassSubtitleController.isSecondaryTextTrackSuppressed());
    }

    void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
        applySecondarySubtitleMode();
        libassSubtitleController.setSecondaryTextTrackSelectionOverride(selection);
    }

    private void applySecondarySubtitleMode() {
        libassSubtitleController.setSecondaryTextTrackAutoSelectionEnabled(SubtitleSetting.getSecondaryMode() == SubtitleSetting.SECONDARY_MODE_AUTO);
    }

    private void applySecondarySubtitleStyle(SubtitleView subtitleView) {
        SubtitleSetting.applyStyle(subtitleView);
        subtitleView.setBottomPosition(0.0f);
        subtitleView.setBottomPaddingFraction(getSecondaryBottomPositionFraction());
    }

    private static float getSecondaryBottomPositionFraction() {
        return (100.0f - SubtitleSetting.getSecondaryPosition()) / 100.0f;
    }
}
