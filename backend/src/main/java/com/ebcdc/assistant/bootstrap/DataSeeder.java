package com.ebcdc.assistant.bootstrap;

import com.ebcdc.assistant.domain.AppUser;
import com.ebcdc.assistant.domain.Provider;
import com.ebcdc.assistant.domain.Role;
import com.ebcdc.assistant.repo.AppUserRepository;
import com.ebcdc.assistant.repo.ProviderRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final AppUserRepository users;
    private final ProviderRepository providers;
    private final PasswordEncoder encoder;

    public DataSeeder(AppUserRepository users, ProviderRepository providers,
                      PasswordEncoder encoder) {
        this.users = users;
        this.providers = providers;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        Provider alpha = providers.findByName("TMA Alpha")
                .orElseGet(() -> providers.save(new Provider("TMA Alpha", "TMA-2026-001", 5)));
        providers.findByName("TMA Beta")
                .orElseGet(() -> providers.save(new Provider("TMA Beta", "TMA-2026-002", 5)));

        String hash = encoder.encode("demo1234");
        if (users.findByEmail("metier@demo.fr").isEmpty()) {
            users.save(new AppUser("metier@demo.fr", "Marie Métier", Role.METIER, hash, null));
        }
        if (users.findByEmail("projet@demo.fr").isEmpty()) {
            users.save(new AppUser("projet@demo.fr", "Paul Projet", Role.PROJET, hash, null));
        }
        if (users.findByEmail("prestataire@demo.fr").isEmpty()) {
            users.save(new AppUser("prestataire@demo.fr", "Sam Prestataire",
                    Role.PRESTATAIRE, hash, alpha));
        }
    }
}
