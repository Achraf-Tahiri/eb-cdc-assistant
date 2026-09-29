-- =====================================================================
-- Jeu de données de démonstration — Registre EB/CDC
--
-- Prérequis : avoir démarré le backend au moins une fois (il crée le
-- schéma et les comptes démo), puis :
--   psql "postgresql://ebcdc:ebcdc@localhost:5433/ebcdc" -f scripts/seed-demo.sql
--
-- Scénario créé :
--  - Doc 1 (TMA Alpha) : cycle complet clôturé, avec UNE clarification
--    tardive (posée 12 j après réception) puis une seconde au compte-
--    gouttes -> alimente M1-M4, M6 et l'alerte "cycles" reste sous le seuil.
--  - Doc 2 (TMA Beta)  : parcours fluide sans clarification (contraste).
--  - Doc 3 (TMA Alpha) : dormant chez le prestataire depuis 9 jours
--    -> déclenche l'alerte "document sans action prestataire".
-- =====================================================================

DO $$
DECLARE
    v_metier  uuid;
    v_projet  uuid;
    v_prest   uuid;
    v_prest_beta uuid;
    v_alpha   uuid;
    v_beta    uuid;
    v_doc1    uuid := gen_random_uuid();
    v_doc2    uuid := gen_random_uuid();
    v_doc3    uuid := gen_random_uuid();
    v_c1      uuid := gen_random_uuid();
    v_c2      uuid := gen_random_uuid();
BEGIN
    SELECT id INTO v_metier FROM users WHERE email = 'metier@demo.fr';
    SELECT id INTO v_projet FROM users WHERE email = 'projet@demo.fr';
    SELECT id INTO v_prest  FROM users WHERE email = 'prestataire@demo.fr';
    SELECT id INTO v_alpha  FROM providers WHERE name = 'TMA Alpha';
    SELECT id INTO v_beta   FROM providers WHERE name = 'TMA Beta';

    IF v_metier IS NULL OR v_projet IS NULL OR v_prest IS NULL THEN
        RAISE EXCEPTION 'Comptes démo absents : démarrez d''abord le backend une fois.';
    END IF;

    IF EXISTS (SELECT 1 FROM documents WHERE title = 'Refonte du module facturation') THEN
        RAISE NOTICE 'Jeu de démonstration déjà présent, rien à faire.';
        RETURN;
    END IF;

    -- L'acteur Beta doit appartenir au contrat Beta, comme l'exige l'API.
    INSERT INTO users (id, email, display_name, role, password_hash, provider_id)
    SELECT gen_random_uuid(), 'prestataire.beta@demo.fr', 'Béa Prestataire',
           'PRESTATAIRE', password_hash, v_beta FROM users WHERE id = v_prest
    ON CONFLICT (email) DO NOTHING;
    SELECT id INTO v_prest_beta FROM users WHERE email = 'prestataire.beta@demo.fr';

    -- ------------------------------------------------------------------
    -- Document 1 : cycle complet avec clarification tardive (TMA Alpha)
    -- ------------------------------------------------------------------
    INSERT INTO documents (id, title, doc_type, project_code, provider_id,
                           current_state, created_by, created_at)
    VALUES (v_doc1, 'Refonte du module facturation', 'EB', 'FACT-2026',
            v_alpha, 'CLOTURE', v_metier, now() - interval '20 days');

    INSERT INTO document_versions (id, document_id, version_num, content, author_id, created_at)
    VALUES (gen_random_uuid(), v_doc1, 1,
            E'# Refonte du module facturation\n\n## Contexte\nLe module actuel date de 2015 et ne gère pas la facturation électronique obligatoire.\n\n## Périmètre\nÉmission, avoirs, relances. La comptabilité analytique est hors périmètre.\n\n## Besoins fonctionnels\nGénération Factur-X, portail de consultation client, relances automatiques à J+30.\n\n## Contraintes\nReprise des 45 000 factures actives. Interfaçage SAP inchangé.\n\n## Critères d''acceptation\n100 % des factures test au format Factur-X valide ; temps de génération < 3 s.\n\n## Délais\nMise en production souhaitée avant la clôture fiscale.',
            v_metier, now() - interval '20 days');

    INSERT INTO workflow_transitions (document_id, from_state, to_state, action,
                                      actor_id, actor_role, comment, occurred_at) VALUES
    (v_doc1, 'BROUILLON', 'BROUILLON', 'CREATE', v_metier, 'METIER',
     'Création du document', now() - interval '20 days'),
    (v_doc1, 'BROUILLON', 'SOUMIS_PROJET', 'SUBMIT', v_metier, 'METIER',
     'Première version complète', now() - interval '19 days'),
    (v_doc1, 'SOUMIS_PROJET', 'VALIDE_PROJET', 'VALIDATE', v_projet, 'PROJET',
     'Périmètre validé', now() - interval '18.5 days'),
    (v_doc1, 'VALIDE_PROJET', 'TRANSMIS_PRESTATAIRE', 'SEND', v_projet, 'PROJET',
     'Envoi TMA Alpha', now() - interval '18 days'),
    (v_doc1, 'TRANSMIS_PRESTATAIRE', 'CLARIFICATION_DEMANDEE', 'REQUEST_CLARIFICATION',
     v_prest, 'PRESTATAIRE', 'Clarification : format des avoirs',
     now() - interval '6 days'),
    (v_doc1, 'CLARIFICATION_DEMANDEE', 'TRANSMIS_PRESTATAIRE', 'RESOLVE', v_projet, 'PROJET',
     'Réponse : avoirs au même format Factur-X', now() - interval '5 days'),
    (v_doc1, 'TRANSMIS_PRESTATAIRE', 'CLARIFICATION_DEMANDEE', 'REQUEST_CLARIFICATION',
     v_prest, 'PRESTATAIRE', 'Clarification : volumétrie des relances',
     now() - interval '2 days'),
    (v_doc1, 'CLARIFICATION_DEMANDEE', 'TRANSMIS_PRESTATAIRE', 'RESOLVE', v_metier, 'METIER',
     'Réponse : 800 relances/mois en moyenne', now() - interval '1 day'),
    (v_doc1, 'TRANSMIS_PRESTATAIRE', 'IMPLEMENTATION_ACCEPTEE', 'ACCEPT', v_prest, 'PRESTATAIRE',
     'Chiffrage accepté', now() - interval '12 hours'),
    (v_doc1, 'IMPLEMENTATION_ACCEPTEE', 'CLOTURE', 'CLOSE', v_projet, 'PROJET',
     'Cycle EB terminé', now() - interval '2 hours');

    INSERT INTO clarification_requests (id, document_id, provider_id, requested_by,
        question, section_ref, requested_at, resolved_at, resolution,
        days_since_provider_received) VALUES
    (v_c1, v_doc1, v_alpha, v_prest,
     'Les avoirs doivent-ils aussi être générés au format Factur-X ?',
     'Besoins fonctionnels', now() - interval '6 days', now() - interval '5 days',
     'Oui, même format que les factures.', 12.0),
    (v_c2, v_doc1, v_alpha, v_prest,
     'Quelle est la volumétrie mensuelle des relances automatiques ?',
     'Contraintes', now() - interval '2 days', now() - interval '1 day',
     'Environ 800 relances par mois.', 3.0);

    -- Rapport de complétude simulé (permet d'illustrer M6 sans attendre le LLM)
    INSERT INTO rag_jobs (id, document_id, job_type, status, result, created_at, finished_at)
    VALUES (gen_random_uuid(), v_doc1, 'COMPLETENESS_CHECK', 'DONE',
            '{"version_analysee":1,"checklist":{"sections_presentes":["Contexte","Périmètre","Besoins fonctionnels","Contraintes","Critères d''acceptation","Délais"],"sections_obligatoires_manquantes":[],"sections_trop_courtes":[]},"sections":[{"titre":"Besoins fonctionnels","analyse":{"ambiguites":["Le périmètre exact du portail client n''est pas défini"],"informations_manquantes":["Volumétrie des relances"],"questions_previsibles_prestataire":["Quelle volumétrie mensuelle de relances ?"],"score_completude":8}},{"titre":"Délais","analyse":{"ambiguites":["La date de clôture fiscale n''est pas précisée"],"informations_manquantes":[],"questions_previsibles_prestataire":[],"score_completude":7}}],"sections_analysees":2,"sections_totales":6}',
            now() - interval '18.3 days', now() - interval '18.2 days');

    -- ------------------------------------------------------------------
    -- Document 2 : parcours fluide sans clarification (TMA Beta)
    -- ------------------------------------------------------------------
    INSERT INTO documents (id, title, doc_type, project_code, provider_id,
                           current_state, created_by, created_at)
    VALUES (v_doc2, 'Portail fournisseurs — lot 2', 'CDC', 'PORT-2026',
            v_beta, 'IMPLEMENTATION_ACCEPTEE', v_metier, now() - interval '10 days');

    INSERT INTO document_versions (id, document_id, version_num, content, author_id, created_at)
    VALUES (gen_random_uuid(), v_doc2, 1,
            E'# Portail fournisseurs — lot 2\n\n## Contexte\nExtension du portail livré au lot 1.\n\n## Périmètre\nDépôt de factures et suivi des paiements.\n\n## Besoins fonctionnels\nDépôt PDF + XML, notifications de statut.\n\n## Contraintes\nSSO d''entreprise obligatoire.\n\n## Critères d''acceptation\nParcours de dépôt en moins de 2 minutes.\n\n## Délais\nLot livrable au prochain trimestre.',
            v_metier, now() - interval '10 days');

    INSERT INTO workflow_transitions (document_id, from_state, to_state, action,
                                      actor_id, actor_role, comment, occurred_at) VALUES
    (v_doc2, 'BROUILLON', 'BROUILLON', 'CREATE', v_metier, 'METIER',
     'Création du document', now() - interval '10 days'),
    (v_doc2, 'BROUILLON', 'SOUMIS_PROJET', 'SUBMIT', v_metier, 'METIER',
     NULL, now() - interval '9 days'),
    (v_doc2, 'SOUMIS_PROJET', 'VALIDE_PROJET', 'VALIDATE', v_projet, 'PROJET',
     NULL, now() - interval '8.5 days'),
    (v_doc2, 'VALIDE_PROJET', 'TRANSMIS_PRESTATAIRE', 'SEND', v_projet, 'PROJET',
     'Envoi TMA Beta', now() - interval '8 days'),
    (v_doc2, 'TRANSMIS_PRESTATAIRE', 'IMPLEMENTATION_ACCEPTEE', 'ACCEPT', v_prest_beta, 'PRESTATAIRE',
     'Accepté sans question', now() - interval '1 day');

    -- ------------------------------------------------------------------
    -- Document 3 : dormant chez le prestataire depuis 9 jours (TMA Alpha)
    -- ------------------------------------------------------------------
    INSERT INTO documents (id, title, doc_type, project_code, provider_id,
                           current_state, created_by, created_at)
    VALUES (v_doc3, 'Interface paie — reprise de données', 'EB', 'PAIE-2026',
            v_alpha, 'TRANSMIS_PRESTATAIRE', v_metier, now() - interval '12 days');

    INSERT INTO document_versions (id, document_id, version_num, content, author_id, created_at)
    VALUES (gen_random_uuid(), v_doc3, 1,
            E'# Interface paie — reprise de données\n\n## Contexte\nMigration du SIRH.\n\n## Périmètre\nReprise des historiques de paie sur 5 ans.\n\n## Besoins fonctionnels\nExtraction, transformation, contrôles de cohérence.\n\n## Contraintes\nDonnées sensibles : anonymisation des environnements de test.\n\n## Critères d''acceptation\nÉcart de contrôle < 0,01 %.\n\n## Délais\nAvant la bascule SIRH.',
            v_metier, now() - interval '12 days');

    INSERT INTO workflow_transitions (document_id, from_state, to_state, action,
                                      actor_id, actor_role, comment, occurred_at) VALUES
    (v_doc3, 'BROUILLON', 'BROUILLON', 'CREATE', v_metier, 'METIER',
     'Création du document', now() - interval '12 days'),
    (v_doc3, 'BROUILLON', 'SOUMIS_PROJET', 'SUBMIT', v_metier, 'METIER',
     NULL, now() - interval '11 days'),
    (v_doc3, 'SOUMIS_PROJET', 'CORRECTION_METIER', 'REQUEST_CORRECTION', v_projet, 'PROJET',
     'Préciser la profondeur d''historique', now() - interval '10.8 days'),
    (v_doc3, 'CORRECTION_METIER', 'SOUMIS_PROJET', 'SUBMIT', v_metier, 'METIER',
     'Historique fixé à 5 ans', now() - interval '10.4 days'),
    (v_doc3, 'SOUMIS_PROJET', 'VALIDE_PROJET', 'VALIDATE', v_projet, 'PROJET',
     NULL, now() - interval '10 days'),
    (v_doc3, 'VALIDE_PROJET', 'TRANSMIS_PRESTATAIRE', 'SEND', v_projet, 'PROJET',
     'Envoi TMA Alpha', now() - interval '9 days');

    RAISE NOTICE 'Jeu de démonstration créé : 3 documents, 2 clarifications, 1 rapport de complétude.';
END $$;
