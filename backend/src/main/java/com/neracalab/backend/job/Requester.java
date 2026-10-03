package com.neracalab.backend.job;

import com.neracalab.backend.auth.AuthenticatedUser;

/**
 * The user who started an ingestion job ({@code ingestion_job.created_by} /
 * {@code created_by_username}). A job without requester is a scheduled run.
 */
public record Requester(long userId, String username) {

    public static Requester of(AuthenticatedUser user) {
        return new Requester(user.userId(), user.username());
    }
}
