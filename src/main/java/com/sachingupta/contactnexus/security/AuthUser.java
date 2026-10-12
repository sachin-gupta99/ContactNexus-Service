package com.sachingupta.contactnexus.security;

import com.sachingupta.contactnexus.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Carries the user id alongside the principal so controllers can scope queries
 * by owner without a second lookup.
 */
public class AuthUser implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;

    public AuthUser(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
    }

    public Long getId() {
        return id;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }
}
