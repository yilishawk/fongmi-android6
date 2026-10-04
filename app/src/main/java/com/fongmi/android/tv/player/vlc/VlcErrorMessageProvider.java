package com.fongmi.android.tv.player.vlc;

import androidx.media3.common.PlaybackException;

public class VlcErrorMessageProvider {

    public String get(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BAD_VALUE -> "VLC Bad Value";
            case PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK -> "VLC Runtime Error";
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> "VLC IO Error";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "VLC Container Unsupported";
            case PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED -> "VLC Audio Init Failed";
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "VLC Decoder Init Failed";
            case PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "VLC Decoder Query Failed";
            case PlaybackException.ERROR_CODE_DECODING_FAILED -> "VLC Decoding Failed";
            default -> "VLC Playback Error";
        };
    }
}
