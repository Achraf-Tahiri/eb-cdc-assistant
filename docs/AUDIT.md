# Audit du 9 septembre 2026

## Verdict

Les parcours testés fonctionnent après correction. Le projet constitue un prototype local démontrable. Cet audit n’est ni une certification de sécurité, ni une preuve que tous les cas possibles fonctionnent, ni une validation scientifique de la qualité du modèle.

### Mise à jour du 13 septembre 2026 — analyse de complétude

Un test utilisateur a montré que l'ancienne analyse isolait chaque section. Elle déclarait donc « format CSV manquant » dans les besoins fonctionnels alors que cette information figurait dans les contraintes. Elle produisait également des ambiguïtés artificielles sur « mois civil » et `AAAA-MM-JJ`.

Le traitement a été remplacé par un appel global : le modèle reçoit les six sections ensemble, une seule fois. Le prompt interdit de réclamer une information présente ailleurs, demande des listes vides quand aucun défaut réel n'existe, limite le nombre de remarques et définit un barème de 0 à 10. Le résultat est validé et normalisé avant affichage ; une section omise est maintenant signalée sans perdre tout le rapport. L'interface explique que le seuil de longueur est un signal et que les notes restent indicatives.

Le nouveau mécanisme a été rejoué sur le même document. Il a analysé 6/6 sections, n'a signalé aucun faux manque ni aucune fausse ambiguïté, et a donné des notes de 8 à 9 avec une justification et des points forts. Temps observé sur CPU : environ 3 min 40 s. Ce test unique confirme la correction du cas reproduit, sans constituer un benchmark général du modèle.

Les essais ont utilisé Java 21, Maven 3.8.7, Node 24.18, PostgreSQL/pgvector dans le conteneur existant et les deux modèles Ollama déjà installés. La base d’origine `ebcdc` n’a pas été utilisée pour les écritures de test. Deux bases distinctes ont été créées : `ebcdc_audit_20260909` pour les régressions et `ebcdc_test_pedagogie` pour les manipulations dans le navigateur.

Avant modification, les sources ont été sauvegardées dans `/tmp/ebcdc-audit-20260909/before-changes.tar.gz` et la base d’origine exportée dans `/tmp/ebcdc-audit-20260909/original-db.sql`. Ces fichiers temporaires peuvent disparaître au nettoyage de `/tmp`.

## Résultats observés

| Vérification | Résultat | Portée |
|---|---|---|
| Backend | 230 tests Java réussis | 216 combinaisons état/action/rôle + 14 cas ciblés |
| API réelle | 21 scénarios réussis | PostgreSQL réel, authentification, droits, parcours, rétention des documents, entrées invalides et concurrence |
| État initial de l’API | 11 échecs sur les 18 premiers scénarios | Les échecs ont été enregistrés avant correction |
| M1–M6 | Valeurs attendues retrouvées | Jeu synthétique aux dates connues ; M6 vérifie une moyenne, pas une corrélation statistique |
| CSV | Endpoint et contenu vérifiés | Séparateur et données ; pas d’ouverture manuelle dans Excel |
| Ingestion | Texte collé, TXT, MD, DOCX et PDF réussis | Petits fichiers synthétiques ; pas de PDF scanné/OCR ou de document complexe |
| Recherche vectorielle | Source attendue retrouvée | Corpus de test restreint, pas de benchmark de recherche |
| Complétude Ollama | DONE, JSON exploitable, bonne version indiquée | Une section, véritable appel à qwen3:4b |
| Suggestion Ollama | Deux jobs DONE sur le document factures | Les sources pertinentes sont retrouvées ; le second essai rejette les deux reformulations déjà présentes |
| Échec du modèle | Job FAILED testé | Simulation unitaire d’une erreur ; arrêt brutal/redémarrage non couvert |
| Frontend | Build de production réussi | Next.js 15.5.25, React 19.2.8 |
| Dépendances npm | 0 vulnérabilité signalée au moment du contrôle | Ce résultat ne couvre pas Maven, le système, Docker ni Ollama |
| Navigateur | Parcours complet avec les trois comptes | Connexion erronée/correcte, création, édition, soumission, validation, transmission, clarification, réponse, acceptation, clôture |
| Corpus et analytique dans le navigateur | Écrans, recherche, insertion de suggestion et nouvelle version vérifiés | Alertes de démonstration, recherche retrouvant le texte indexé, M5 affichant 15,5 jours prestataire sur l’exemple facturation |
| Seed SQL | Deux exécutions sur base pédagogique | Trois documents créés une seule fois ; compte Beta cohérent avec son contrat |
| Démarrage et arrêt | Application réellement lancée puis arrêtée par les scripts corrigés | Refus vérifié si un port est occupé ; arrêt des deux groupes du projet et ports libérés |

Preuves : [résultats conservés](audit-2026-09-09/). Le compte élevé de tests Java vient principalement de la matrice des transitions : il ne représente pas 230 parcours complets.

## Corrections effectuées

1. **Contournement des clarifications.** `RESOLVE` pouvait renvoyer un document sans aucune réponse. Le service refuse désormais tant qu’il reste des questions ouvertes, y compris avant acceptation. Le bouton de renvoi direct a été retiré de l’écran.
2. **Cycle sans question.** Un appel direct à `REQUEST_CLARIFICATION` pouvait créer un cycle vide. Une question ouverte est désormais nécessaire.
3. **Questions après acceptation/clôture.** L’API acceptait ces questions hors workflow. Les états autorisés sont maintenant contrôlés.
4. **Concurrence des clarifications.** Création et résolution utilisent le même verrou documentaire avant chargement/modification des questions. Deux questions simultanées créent un seul cycle ; les dernières réponses concurrentes produisent un seul retour.
5. **Transmission sans prestataire.** Refus explicite au lieu de rendre le document inaccessible aux prestataires. L’interface ne permet pas encore d’affecter ultérieurement un prestataire : choisir le contrat à la création pour un parcours complet.
6. **Prestataire sans contrat.** La liste retournait tous les documents dans ce cas. Elle retourne désormais une liste vide ; test unitaire dédié.
7. **Entrées invalides.** Titre/type/action vides, question/réponse blanche et contenu nul reçoivent une erreur explicite. Un job visant un document inexistant renvoie 404. La validation générale de toutes les routes reste à compléter.
8. **Authentification.** Les requêtes anonymes reçoivent 401 ; une erreur interne n’est plus masquée par un refus d’accès lors du dispatch d’erreur. La connexion avec un mauvais mot de passe affiche son véritable message.
9. **Faux message d’erreur prestataire.** La fiche prestataire appelait la route interne des jobs RAG et affichait un refus. Elle ne fait plus cet appel.
10. **Modifications non enregistrées.** Un changement d’état depuis l’éditeur exige désormais d’enregistrer d’abord les modifications visibles. Cela ne protège pas encore tous les départs de page.
11. **Checklist.** Conservation des sections vides et suppression du rapprochement inversé qui pouvait accepter des titres partiels comme « C ». Prise en charge de la casse, des accents, des apostrophes typographiques et d’une numérotation simple.
12. **Seuil tardif.** La couleur de clarification est calculée côté backend à partir du seuil configuré. Le texte analytique ne promet plus un seuil fixe de cinq jours.
13. **Interprétation M6.** Retrait de l’affirmation infondée qu’une bonne note IA invalide l’existence d’ambiguïtés.
14. **Seed de démonstration.** L’acceptation du document Beta était attribuée au compte Alpha. Le seed crée désormais un compte Beta dédié. Les données déjà présentes ne sont pas réécrites : la correction concerne une nouvelle initialisation.
15. **Scripts de lancement.** Suppression des `fuser -k` globaux et de l’arrêt/reconfiguration d’Open WebUI. Seuls les groupes de processus du projet, identifiés par PID et instant de création, sont ciblés à l’arrêt. Le seed devient explicite (`EBCDC_SEED_DEMO=1`, uniquement pour la base standard).
16. **Dépendances frontend.** Passage de Next 14.2.35/React 18 à Next 15.5.25/React 19.2.8. PostCSS est forcé à 8.5.28 via `overrides` car Next 15 épingle une ancienne version. Installation et build validés, audit npm à zéro.
17. **Suggestions RAG.** Le document entier accompagne désormais la section ciblée. Un seuil élimine les passages trop éloignés. Les citations sont des identifiants contrôlés puis remplacés par les métadonnées de PostgreSQL. Le serveur retire les références inventées, les textes faiblement soutenus et les reformulations déjà présentes. Un résultat vide est présenté comme tel au lieu d'inventer un conseil.
18. **Réimport du corpus.** Une source portant le même nom remplace ses anciens passages dans une transaction. Un échec d'insertion restaure l'état précédent.

La mise à jour Next s’appuie sur le [bulletin officiel d’août 2026](https://nextjs.org/blog/august-2026-security-release) et le [guide de migration Next 15](https://nextjs.org/docs/app/guides/upgrading/version-15). Les alertes du scanner décrivent des versions affectées ; elles ne prouvent pas que chaque faille était exploitable dans la configuration de ce projet.

## Limites encore présentes

### Avant une utilisation réelle

- Clé JWT et comptes de démonstration publics, absence d’administration des comptes et de limitation des tentatives de connexion. Ne pas exposer tel quel ce prototype sur Internet.
- Dépendances backend anciennes : audit Maven/CVE exhaustif non réalisé dans cette passe. Le résultat npm ne concerne que le frontend.
- Le rôle projet peut modifier le contenu après validation et même après clôture. Faire valider cette règle par le responsable métier ; une révision devrait idéalement repasser en validation.
- Les jobs interrompus en RUNNING restent bloqués au redémarrage ; pas de reprise automatique ni de coordination fiable entre plusieurs instances.
- La version de complétude est choisie quand le worker commence, pas figée à la demande. L’analytique peut afficher une note d’une version différente du document actuel.
- Le corpus n’a pas d’écran de suppression ni de véritable historique de versions. La déduplication repose sur l'égalité exacte du nom de source. Le plafond de 300 chunks tronque silencieusement les gros documents.
- Les réponses LLM sont contrôlées structurellement et les suggestions passent des filtres lexicaux, mais ces contrôles ne prouvent pas leur vérité métier. Pas de campagne complète de tests adversariaux de prompt injection.
- Aucun test exhaustif d’accessibilité, de mobile, de charge, de sauvegarde/restauration, de fichiers malveillants ni de panne en plein traitement.

### Pour défendre la partie data science

- Le corpus et les scores de démonstration sont synthétiques. Aucune mesure sur les données métier réelles ne prouve un gain de temps ou de qualité.
- La complétude n’est pas une vérité terrain. L'analyse globale a corrigé le cas reproduit où le modèle redemandait des informations présentes dans d'autres sections, mais d'autres documents peuvent encore provoquer des erreurs.
- Les suggestions affichent désormais les vraies sources retenues et leur score. Cette traçabilité permet la relecture, mais ne prouve pas que le passage cité justifie parfaitement chaque phrase.
- M6 est un tableau descriptif, sans Pearson/Spearman, intervalle de confiance, contrôle de version ou analyse causale.
- M1 moyenne les intervalles, M3 inclut les brouillons affectés, M5 mesure du temps écoulé. Le libellé « prestataire (implémentation) » après acceptation est une convention, pas une observation de travail effectif.

Prochaine évaluation utile : constituer un petit corpus autorisé et un jeu de questions annoté par un référent métier, mesurer Recall@5 pour la recherche, pertinence/fidélité des suggestions, précision des alertes de complétude, erreurs de citation et latence. Comparer avec une checklist seule et une génération sans recherche. La validation humaine doit rester distincte de la note du modèle.

## Rejouer les tests

Depuis la racine :

```bash
mvn -B -f backend/pom.xml test
(cd frontend && npm ci && npm run build && npm audit)
```

Les tests HTTP créent des documents et conservent leurs traces. Démarrer un backend sur une base jetable initialisée par l’application, pas sur `ebcdc` :

```bash
docker exec ebcdc-db createdb -U ebcdc ebcdc_test_local
# Les ports 8080/3000 doivent être libres.
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ebcdc_test_local ./demarrer.sh
# Dans un autre terminal :
EBCDC_TEST_URL=http://localhost:8080 python3 scripts/test-api.py
EBCDC_TEST_URL=http://localhost:8080 EBCDC_TEST_DB=ebcdc_test_local python3 scripts/test-analytics.py
EBCDC_TEST_URL=http://localhost:8080 python3 scripts/test-rag.py
```

Le nom de base doit être neuf pour `createdb`. `test-analytics.py` insère ses propres fixtures SQL dans le conteneur `ebcdc-db` ; l’URL HTTP doit pointer vers le backend utilisant cette même base. Le script RAG peut prendre plusieurs minutes et teste TXT/MD automatiquement ; les petits DOCX/PDF ont été générés et vérifiés séparément pendant cet audit.

Pour revenir à la base pédagogique : arrêter avec `./arreter.sh`, puis lancer `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/ebcdc_test_pedagogie ./demarrer.sh`. Pour retrouver les données d’origine, utiliser `./demarrer.sh` sans cette variable dans un nouveau terminal.
