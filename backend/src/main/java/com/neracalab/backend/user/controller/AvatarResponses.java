package com.neracalab.backend.user.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.neracalab.backend.user.UserRepository.Avatar;

/** The response of a profile picture: its detected image type, never cached by shared caches, no sniffing. */
final class AvatarResponses {

    private AvatarResponses() {
    }

    static ResponseEntity<byte[]> of(Avatar avatar) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(avatar.contentType()))
                .contentLength(avatar.content().length)
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(avatar.content());
    }
}
