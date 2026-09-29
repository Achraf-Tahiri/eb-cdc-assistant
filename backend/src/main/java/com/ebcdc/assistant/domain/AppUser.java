package com.ebcdc.assistant.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class AppUser {
    @Id
    private UUID id = UUID.randomUUID();
    @Column(unique = true, nullable = false)
    private String email;
    @Column(name = "display_name", nullable = false)
    private String displayName;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "provider_id")
    private Provider provider;
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    public AppUser() {}
    public AppUser(String email, String displayName, Role role, String passwordHash, Provider provider) {
        this.email = email; this.displayName = displayName; this.role = role;
        this.passwordHash = passwordHash; this.provider = provider;
    }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public Role getRole() { return role; }
    public String getPasswordHash() { return passwordHash; }
    public Provider getProvider() { return provider; }
}
