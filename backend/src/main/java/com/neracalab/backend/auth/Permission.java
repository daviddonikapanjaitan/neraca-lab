package com.neracalab.backend.auth;

/**
 * What a role allows ({@code role_permissions.permission}). A user has the permissions of all
 * their roles.
 */
public enum Permission {

    ADMIN("Admin", "Admin center: user management and role management pages and APIs"),
    INGESTION("Ingestion", "Ingestion page and APIs: financial statement uploads, price ingestion, jobs, file downloads"),
    COMPANIES("Companies", "Companies pages and APIs: company list and company detail");

    private final String label;
    private final String description;

    Permission(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }
}
